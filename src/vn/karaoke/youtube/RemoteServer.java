package vn.karaoke.youtube;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP control server on port 8792. ServerSocket + HttpURLConnection +
 * org.json only. Both HTML pages are string constants (no assets folder).
 * Clean rewrite from the behaviour spec.
 *
 * Endpoints: /api/state /api/search /api/control /api/enqueue /api/playnow
 * /api/paste /api/channel /api/playlists /api/playlist /api/playlist-add
 * /api/playlist-create /api/playlist-delete /api/playlist-enqueue
 * /api/playlist-play /api/playlist-remove /api/pinggy /api/qr /stage /stream
 */
public class RemoteServer {
    public static final int PORT = 8792;

    private final Context ctx;
    private final LibraryState library;
    private final MesuPlayer player;
    private final YoutubeApi api = new YoutubeApi();
    private PinggyTunnel tunnel;
    private PlayerService service;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running = false;
    /** v1.0.29: server-side paging for /api/search — YoutubeApi.search()
     * has no paging (it returns the whole first page), but the real Gathin
     * panel pages through results 20 at a time and stops only when a page
     * comes back with < 20 items. Cache the last query + results so page 2+
     * returns the next slice (and an empty slice past the end, which is
     * what terminates the panel's infinite scroll). Guarded by sync on this. */
    private String lastSearchQuery = null;
    private List<YoutubeApi.Video> lastSearchResults = null;
    private volatile int boundPort = -1;
    /** Port the server actually bound to (8792, or 8793-8795 if 8792 was busy). */
    public int getPort() { return boundPort > 0 ? boundPort : PORT; }
    public String stageUrl() { return "http://127.0.0.1:" + getPort() + "/stage"; }

    public RemoteServer(Context ctx, LibraryState library, MesuPlayer player) {
        this.ctx = ctx.getApplicationContext();
        this.library = library;
        this.player = player;
    }
    public void setTunnel(PinggyTunnel t) { tunnel = t; }
    public void setService(PlayerService s) { service = s; }
    public boolean isRunning() { return running; }

    public synchronized void start() {
        if (running) return;
        running = true;
        pool = Executors.newCachedThreadPool();
        new Thread(new Runnable() {
            @Override public void run() {
            try {
                // Port 8792 may already be held (old service instance, Gathin
                // tvhome on the same box). Try 8792-8795 and remember the one
                // that bound, so the stage loads from the right port.
                for (int p = PORT; p < PORT + 4; p++) {
                    try { server = new ServerSocket(p); boundPort = p; break; }
                    catch (Exception e) { server = null; }
                }
                if (server == null) { running = false; StartupLog.log(ctx, "RemoteServer bind THAT BAI 8792-8795"); return; }
                StartupLog.log(ctx, "RemoteServer bind OK port=" + boundPort);
                while (running) {
                    final Socket s = server.accept();
                    pool.execute(new Runnable() {
                        @Override public void run() { handle(s); }
                    });
                }
            } catch (Exception e) { running = false; StartupLog.log(ctx, "RemoteServer thread chet: " + e); }
            }
        }, "remote-server").start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    public String localIp() {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Exception e) { return "127.0.0.1"; }
    }
    public String lanUrl() { return "http://" + localIp() + ":" + PORT; }

    // ---------- request handling ----------

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
            OutputStream out = sock.getOutputStream();
            String reqLine = in.readLine();
            if (reqLine == null || reqLine.isEmpty()) { sock.close(); return; }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
                if (line.toLowerCase().startsWith("content-length:"))
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) { }
            }
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }
            String path = rawPath.split("\\?")[0];
            Map<String, String> q = parseQuery(rawPath.contains("?") ? rawPath.substring(rawPath.indexOf('?') + 1) : "");
            if (!body.isEmpty()) {
                for (Map.Entry<String, String> e : parseQuery(body).entrySet()) q.putIfAbsent(e.getKey(), e.getValue());
            }
            route(method, path, q, headers, body, out);
            out.flush();
            sock.close();
        } catch (Exception e) {
            // v1.0.15: never die silently — try to answer with a 500 JSON body
            // so the caller (e.g. the phone panel) sees an error instead of a
            // connection that just closes with no response.
            try {
                OutputStream out2 = sock.getOutputStream();
                JSONObject err = new JSONObject();
                String msg = e.getMessage();
                err.put("error", msg == null ? e.toString() : msg);
                byte[] body = err.toString().getBytes("UTF-8");
                String head = "HTTP/1.1 500 OK\r\nContent-Type: application/json; charset=utf-8" +
                        "\r\nContent-Length: " + body.length +
                        "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
                out2.write(head.getBytes("UTF-8"));
                out2.write(body);
                out2.flush();
            } catch (Exception ignored) { }
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    private Map<String, String> parseQuery(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
                else if (!pair.isEmpty()) m.put(URLDecoder.decode(pair, "UTF-8"), "");
            } catch (Exception ignored) { }
        }
        return m;
    }

    private void send(OutputStream out, int code, String contentType, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + contentType +
                "\r\nContent-Length: " + body.length +
                "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
    }
    private void sendJson(OutputStream out, JSONObject o) throws Exception {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
    }
    private void sendHtml(OutputStream out, String html) throws Exception {
        send(out, 200, "text/html; charset=utf-8", html.getBytes("UTF-8"));
    }

    /** v1.0.15: each field is guarded on its own — one bad field can never
     * blank the whole state again (before, a single exception anywhere made
     * stateJson() return {} and the phone panel looked dead). */
    private JSONObject stateJson() {
        JSONObject o = new JSONObject();
        try { o.put("player", player.getState()); } catch (Exception e) { safePut(o, "player", "-1,0,0,0,0,"); }
        try { o.put("error", player.getLastError()); } catch (Exception e) { safePut(o, "error", ""); }
        try {
            LibraryState.Item cur = library.current();
            o.put("current", cur == null ? JSONObject.NULL : cur.toJson());
        } catch (Exception e) { safePut(o, "current", JSONObject.NULL); }
        try {
            JSONArray q = new JSONArray();
            for (LibraryState.Item it : library.queue) q.put(it.toJson());
            o.put("queue", q);
        } catch (Exception e) { safePut(o, "queue", new JSONArray()); }
        try { o.put("currentIndex", library.currentIndex); } catch (Exception e) { safePut(o, "currentIndex", 0); }
        // v1.0.29: the fields the real Gathin panel's refresh() reads.
        // player.getState() is "st,pos,dur,w,h,f" (pos/dur in MILLISECONDS);
        // the panel wants seconds. playing = st==1. Volume comes from the
        // last setVolume() (MesuPlayer tracks it — ExoPlayer has no getter).
        try {
            String st = player.getState();
            String[] sp = st.split(",");
            int code = 0; long pos = 0, dur = 0;
            try { code = Integer.parseInt(sp[0]); } catch (Exception ignored) { }
            try { pos = Long.parseLong(sp[1]); } catch (Exception ignored) { }
            try { dur = Long.parseLong(sp[2]); } catch (Exception ignored) { }
            o.put("playing", code == 1);
            o.put("position", pos / 1000);
            o.put("duration", dur / 1000);
        } catch (Exception e) {
            safePut(o, "playing", false); safePut(o, "position", 0); safePut(o, "duration", 0);
        }
        try { o.put("volume", player.getVolumePercent()); } catch (Exception e) { safePut(o, "volume", 100); }
        try { o.put("sleepLeft", service == null ? 0 : service.sleepMinutesLeft()); } catch (Exception e) { safePut(o, "sleepLeft", 0); }
        try { o.put("index", library.currentIndex); } catch (Exception e) { safePut(o, "index", 0); }
        try { o.put("lanUrl", lanUrl()); } catch (Exception e) { safePut(o, "lanUrl", ""); }
        try { o.put("pinggyUrl", tunnel == null ? "" : tunnel.getPublicUrl()); } catch (Exception e) { safePut(o, "pinggyUrl", ""); }
        try { o.put("pinggyState", tunnel == null ? "off" : tunnel.getState()); } catch (Exception e) { safePut(o, "pinggyState", "off"); }
        try { o.put("pinggyEnabled", tunnel != null && tunnel.isEnabled()); } catch (Exception e) { safePut(o, "pinggyEnabled", false); }
        try { o.put("sleepMinutesLeft", service == null ? 0 : service.sleepMinutesLeft()); } catch (Exception e) { safePut(o, "sleepMinutesLeft", 0); }
        // v1.0.27: self-update status for the panel (one row + toggle).
        try {
            UpdateChecker u = service == null ? null : service.getUpdateChecker();
            o.put("autoUpdate", u == null ? UpdateChecker.isAutoUpdateEnabled(ctx) : u.isAutoUpdate());
            o.put("updateAvailable", u != null && u.isAvailable());
            o.put("updateVersion", u == null ? "" : u.getRemoteVersionName());
            o.put("updateState", u == null ? "none" : u.getState());
        } catch (Exception e) {
            safePut(o, "autoUpdate", true); safePut(o, "updateAvailable", false);
            safePut(o, "updateVersion", ""); safePut(o, "updateState", "none");
        }
        return o;
    }

    /** Best-effort put — never throws, so stateJson() always returns a full object. */
    private static void safePut(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (Exception ignored) { }
    }

    /** Build an Item from request params. The real Gathin panel POSTs the
     * whole item as form fields: id/title/artist/thumb/duration (SECONDS).
     * The old simplified panel used channel/durationMs — accept both. */
    private LibraryState.Item itemFromParams(Map<String, String> q) {
        String id = q.get("id");
        if (id == null || id.isEmpty()) id = YoutubeApi.extractId(q.get("url"));
        if (id == null || id.isEmpty()) id = YoutubeApi.extractId(q.get("q"));
        if (id == null) return null;
        String channel = q.get("channel");
        if (channel == null || channel.isEmpty()) channel = q.get("artist");
        long dur = 0;
        try {
            String dms = q.get("durationMs");
            if (dms != null) dur = Long.parseLong(dms);
            else if (q.get("duration") != null) dur = Long.parseLong(q.get("duration")) * 1000;
        } catch (Exception ignored) { }
        String thumb = q.get("thumb");
        return new LibraryState.Item(id, q.get("title"), channel, dur, thumb);
    }

    /** Resolve a playlist by the Gathin panel's "playlist" param (stable id),
     * falling back to the "name" param the old panel used. "new" is NOT a
     * playlist — it means create (handled by the caller). */
    private LibraryState.Playlist resolvePlaylistParam(Map<String, String> q) {
        String pid = q.get("playlist");
        if (pid != null && !pid.isEmpty() && !"new".equals(pid)) {
            LibraryState.Playlist pl = library.findPlaylistById(pid);
            if (pl != null) return pl;
        }
        String name = q.get("name");
        if (name != null && !name.isEmpty()) return library.findPlaylistByName(name);
        if (pid != null && !pid.isEmpty() && !"new".equals(pid))
            return library.findPlaylistByName(pid); // tolerate an id-shaped name
        return null;
    }

    /** v1.0.26: route a player mutation through PlayerService.playerAction()
     * (the main-thread choke point). The service-null fallback is direct —
     * the same unreachable-in-practice pattern as playItem() (the service
     * is set before server.start()). Direct MesuPlayer mutations from the
     * HTTP layer were the bug: ExoPlayer throws "Player is accessed on the
     * wrong thread" when called from a pool thread (proven live 2026-11-28
     * 11:01 on /api/control?action=toggle and ?action=stop). */
    private void controlPause() { if (service != null) service.pausePlayer(); else player.pause(); }
    private void controlResume() { if (service != null) service.resumePlayer(); else player.resume(); }
    private void controlStop() { if (service != null) service.stopPlayer(); else player.stop(); }
    private void controlVolume(float v) { if (service != null) service.setPlayerVolume(v); else player.setVolume(v); }
    private void controlSeek(long ms) { if (service != null) service.seekPlayer(ms); else player.seekTo(ms); }

    private void playItem(LibraryState.Item it) {
        if (it == null) return;
        library.recordPlay(it);
        // v1.0.24 ROOT CAUSE: this used to call player.play() DIRECTLY,
        // bypassing PlayerService.playNow() entirely — so the v1.0.21
        // bring-to-front AND the v1.0.23 full-screen intent never fired
        // for ANY panel play (/api/playnow, /api/paste, next/prev, jump,
        // playlist): exit app -> choose video -> audio only, every time,
        // while the fixes sat unused in playNow(). playNow() is now the
        // true single choke point for every play start (it calls
        // player.play() itself + onStartedPlaying()). Direct play is kept
        // only as a defensive fallback when the service reference is null
        // (never in practice: PlayerService sets it before server.start()).
        if (service != null) {
            service.playNow(it);
            return;
        }
        // Defensive fallback only (service is null): play directly.
        player.play(it.id, it.title == null ? "" : it.title);
    }

    private void route(String method, String path, Map<String, String> q,
                       Map<String, String> headers, String body, OutputStream out) throws Exception {
        switch (path) {
            case "/": sendHtml(out, PAGE); return; // v1.0.18: root serves the panel, not the bare stage (white page on phones)
            case "/stage": sendHtml(out, STAGE_HTML); return;
            case "/panel": sendHtml(out, PAGE); return;
            case "/api/state": sendJson(out, stateJson()); return;
            case "/api/qr": {
                String u = q.get("u");
                if (u == null || u.isEmpty()) u = lanUrl();
                byte[] png = QrCode.png(u, 360);
                if (png == null) { send(out, 500, "text/plain", "qr failed".getBytes("UTF-8")); return; }
                send(out, 200, "image/png", png);
                return;
            }
            case "/api/search": {
                String query = q.get("q");
                if (query == null || query.isEmpty()) { sendJson(out, new JSONObject().put("ok", false).put("error", "empty query")); return; }
                // v1.0.29: channel search is CUT in v1 — the panel shows
                // "Không thấy kênh nào." for an empty results list.
                if ("channel".equals(q.get("type"))) {
                    sendJson(out, new JSONObject().put("ok", true).put("results", new JSONArray()));
                    return;
                }
                int page = 1;
                try { page = Math.max(1, Integer.parseInt(q.get("page"))); } catch (Exception ignored) { }
                List<YoutubeApi.Video> res;
                synchronized (this) {
                    res = (query.equals(lastSearchQuery) && lastSearchResults != null) ? lastSearchResults : null;
                }
                if (res == null) {
                    library.recordSearch(query);
                    try {
                        res = api.search(query);
                    } catch (Exception e) {
                        sendJson(out, new JSONObject().put("ok", false).put("error", "Không tìm được: " + e.getMessage()));
                        return;
                    }
                    synchronized (this) { lastSearchQuery = query; lastSearchResults = res; }
                }
                // v1.0.29: page slices of 20 — the panel stops paging only
                // when a page returns < 20 items, so the slice past the end
                // (empty) is what terminates its infinite scroll. Without
                // paging the same 20 results would come back forever.
                JSONArray arr = new JSONArray();
                int from = (page - 1) * 20;
                if (res != null && from < res.size())
                    for (int i = from; i < Math.min(from + 20, res.size()); i++) arr.put(res.get(i).toJson());
                sendJson(out, new JSONObject().put("ok", true).put("results", arr));
                return;
            }
            case "/api/control": {
                String action = q.get("action");
                if (action == null) action = q.get("a");
                if ("toggle".equals(action)) {
                    String st = player.getState();
                    if (st.startsWith("1,")) controlPause();
                    // v1.0.25: resuming from the panel clears a previous
                    // Back-dismiss so the overlay video shows again (the
                    // endCheck poll re-shows it within ~3 s).
                    else { controlResume(); if (service != null) service.clearVideoOverlayDismissed(); }
                } else if ("pause".equals(action)) controlPause();
                else if ("resume".equals(action) || "play".equals(action)) { controlResume(); if (service != null) service.clearVideoOverlayDismissed(); }
                else if ("next".equals(action)) { LibraryState.Item n = library.next(); playItem(n); }
                else if ("prev".equals(action)) { LibraryState.Item p = library.prev(); playItem(p); }
                else if ("stop".equals(action)) controlStop();
                else if ("volume".equals(action)) {
                    // v1.0.29: the Gathin panel posts value=0-100; the old
                    // panel used v= — accept both.
                    String vs = q.get("value");
                    if (vs == null) vs = q.get("v");
                    try { controlVolume(Float.parseFloat(vs) / 100f); } catch (Exception ignored) { }
                } else if ("seek".equals(action)) {
                    try { controlSeek(Long.parseLong(q.get("pos"))); } catch (Exception ignored) { }
                } else if ("sleep".equals(action)) {
                    try { if (service != null) service.setSleepMinutes(Integer.parseInt(q.get("minutes"))); } catch (Exception ignored) { }
                } else if ("remove".equals(action)) {
                    try { library.removeAt(Integer.parseInt(q.get("index"))); } catch (Exception ignored) { }
                } else if ("move".equals(action)) {
                    try { library.move(Integer.parseInt(q.get("from")), Integer.parseInt(q.get("to"))); } catch (Exception ignored) { }
                } else if ("jump".equals(action)) {
                    try { int i = Integer.parseInt(q.get("index")); library.jumpTo(i); playItem(library.current()); } catch (Exception ignored) { }
                } else if ("clear".equals(action)) { library.clearQueue(); controlStop(); }
                // v1.0.26: let the listener-updated state settle before
                // answering — setPlayWhenReady() notifies onIsPlayingChanged
                // asynchronously on the main thread, so without this the
                // reply could still show the old state code ("1" right
                // after a pause). 300 ms is plenty and the panel is
                // human-paced.
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                sendJson(out, stateJson());
                return;
            }
            case "/api/enqueue": {
                LibraryState.Item it = itemFromParams(q);
                if (it == null) { sendJson(out, new JSONObject().put("error", "no id")); return; }
                library.enqueue(it);
                sendJson(out, stateJson());
                return;
            }
            case "/api/playnow": {
                LibraryState.Item it = itemFromParams(q);
                if (it == null) { sendJson(out, new JSONObject().put("error", "no id")); return; }
                library.enqueueAtFront(it);
                playItem(it);
                sendJson(out, stateJson());
                return;
            }
            case "/api/paste": {
                String id = YoutubeApi.extractId(q.get("url"));
                if (id == null) id = YoutubeApi.extractId(q.get("q"));
                if (id == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Không phải link YouTube")); return; }
                // v1.0.29: the Gathin panel posts mode=now (play immediately)
                // or mode=queue, and reads s.ok && s.item.title for its note.
                String mode = q.get("mode");
                boolean playNow = "playnow".equals(mode) || "now".equals(mode);
                LibraryState.Item it;
                try {
                    JSONObject r = new JSONObject(new MesuResolver().resolve(id));
                    it = new LibraryState.Item(id,
                            r.optString("title", ""), r.optString("channel", ""), r.optLong("durationMs", 0));
                } catch (Exception e) {
                    sendJson(out, new JSONObject().put("ok", false).put("error", "Không lấy được video: " + e.getMessage()));
                    return;
                }
                if (playNow) { library.enqueueAtFront(it); playItem(it); }
                else library.enqueue(it);
                sendJson(out, new JSONObject().put("ok", true).put("item", it.toJson()));
                return;
            }
            case "/api/channel": {
                // v1: channel view is cut; the panel shows its
                // "Không lấy được video của kênh." message when ok is false.
                sendJson(out, new JSONObject().put("ok", false).put("error", "Kênh chưa hỗ trợ trong bản này"));
                return;
            }
            case "/api/playlists": {
                JSONArray arr = new JSONArray();
                for (LibraryState.Playlist pl : library.playlists) {
                    JSONObject p = new JSONObject();
                    p.put("id", pl.id);
                    p.put("name", pl.name);
                    p.put("count", pl.items.size());
                    JSONArray items = new JSONArray();
                    for (LibraryState.Item it : pl.items) items.put(it.toJson());
                    p.put("items", items);
                    arr.put(p);
                }
                sendJson(out, new JSONObject().put("ok", true).put("playlists", arr));
                return;
            }
            case "/api/playlist": {
                // v1.0.29: resolve by stable id (?id=) like the Gathin panel
                // sends; the name param still works for the old panel.
                LibraryState.Playlist found = null;
                if (q.get("id") != null) found = library.findPlaylistById(q.get("id"));
                if (found == null && q.get("name") != null) found = library.findPlaylistByName(q.get("name"));
                if (found == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Không thấy playlist")); return; }
                JSONArray items = new JSONArray();
                for (LibraryState.Item it : found.items) items.put(it.toJson());
                sendJson(out, new JSONObject().put("ok", true).put("name", found.name).put("items", items));
                return;
            }
            case "/api/playlist-create": {
                String name = q.get("name");
                if (name == null || name.isEmpty()) { sendJson(out, new JSONObject().put("ok", false).put("error", "Thiếu tên playlist")); return; }
                LibraryState.Playlist pl = library.getOrCreatePlaylist(name);
                sendJson(out, new JSONObject().put("ok", true).put("id", pl.id).put("name", pl.name));
                return;
            }
            case "/api/playlist-delete": {
                // v1.0.29: accept the stable id (playlist=) as well as name=.
                LibraryState.Playlist pl = resolvePlaylistParam(q);
                if (pl == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Không thấy playlist")); return; }
                library.deletePlaylistById(pl.id);
                sendJson(out, new JSONObject().put("ok", true));
                return;
            }
            case "/api/playlist-add": {
                // v1.0.29: the Gathin panel posts playlist=<id> (+ name only
                // when creating: playlist=new, name=<new name>) and the item
                // fields id/title/artist/thumb/duration. added counts only
                // items actually added (0 = already in the playlist).
                LibraryState.Item it = itemFromParams(q);
                if (it == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Thiếu video")); return; }
                LibraryState.Playlist pl = null;
                if ("new".equals(q.get("playlist"))) {
                    String name = q.get("name");
                    if (name == null || name.isEmpty()) { sendJson(out, new JSONObject().put("ok", false).put("error", "Thiếu tên playlist")); return; }
                    pl = library.getOrCreatePlaylist(name);
                } else {
                    pl = resolvePlaylistParam(q);
                    if (pl == null) {
                        String name = q.get("name");
                        if (name != null && !name.isEmpty()) pl = library.getOrCreatePlaylist(name);
                    }
                }
                if (pl == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Không thấy playlist")); return; }
                boolean added = library.playlistAdd(pl, it);
                sendJson(out, new JSONObject().put("ok", true).put("added", added ? 1 : 0).put("name", pl.name));
                return;
            }
            case "/api/playlist-remove": {
                // v1.0.29: remove by item id (playlist=<id>, id=<video id>);
                // the old name + index path still works.
                LibraryState.Playlist pl = resolvePlaylistParam(q);
                if (pl == null) { sendJson(out, new JSONObject().put("ok", false).put("error", "Không thấy playlist")); return; }
                boolean removed = false;
                String itemId = q.get("id");
                if (itemId != null && !itemId.isEmpty()) {
                    for (int i = 0; i < pl.items.size(); i++)
                        if (itemId.equals(pl.items.get(i).id)) { pl.items.remove(i); removed = true; break; }
                } else {
                    try { int idx = Integer.parseInt(q.get("index")); if (idx >= 0 && idx < pl.items.size()) { pl.items.remove(idx); removed = true; } } catch (Exception ignored) { }
                }
                if (removed) library.persist();
                sendJson(out, new JSONObject().put("ok", removed));
                return;
            }
            case "/api/playlist-enqueue": {
                LibraryState.Playlist pl = resolvePlaylistParam(q);
                if (pl == null || pl.items.isEmpty()) { sendJson(out, new JSONObject().put("ok", false).put("error", "Playlist đang trống")); return; }
                for (LibraryState.Item it : pl.items) library.enqueue(it);
                sendJson(out, new JSONObject().put("ok", true));
                return;
            }
            case "/api/playlist-play": {
                // v1.0.29: resolve by id; optional index (default 0).
                LibraryState.Playlist pl = resolvePlaylistParam(q);
                if (pl == null || pl.items.isEmpty()) { sendJson(out, new JSONObject().put("ok", false).put("error", "Playlist đang trống")); return; }
                int idx = 0;
                try { idx = Integer.parseInt(q.get("index")); } catch (Exception ignored) { }
                if (idx < 0 || idx >= pl.items.size()) idx = 0;
                library.clearQueue();
                for (LibraryState.Item it : pl.items) library.enqueue(it);
                library.jumpTo(idx);
                playItem(library.current());
                sendJson(out, new JSONObject().put("ok", true));
                return;
            }
            case "/api/pinggy": {
                if (tunnel == null) { sendJson(out, new JSONObject().put("error", "no tunnel")); return; }
                String action = q.get("action");
                if ("enable".equals(action)) tunnel.setEnabled(true);
                else if ("disable".equals(action)) tunnel.setEnabled(false);
                else if ("auth".equals(action)) tunnel.setAuth(q.get("user"), q.get("pass"));
                else if ("restart".equals(action)) { tunnel.stop(); tunnel.start(); }
                JSONObject o = new JSONObject();
                o.put("url", tunnel.getPublicUrl());
                o.put("state", tunnel.getState());
                o.put("enabled", tunnel.isEnabled());
                sendJson(out, o);
                return;
            }
            case "/api/overlay": {
                String action = q.get("action");
                if ("enable".equals(action)) QrOverlayService.setEnabled(ctx, true);
                else if ("disable".equals(action)) QrOverlayService.setEnabled(ctx, false);
                else if ("grant".equals(action)) {
                    // Open the system overlay-permission screen for this app:
                    // without this grant the floating QR can never appear, and
                    // there is no in-app prompt for it since v1.0.5.
                    try {
                        Intent gi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + ctx.getPackageName()));
                        gi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(gi);
                        StartupLog.log(ctx, "Mo man hinh cap quyen QR noi");
                    } catch (Exception e) {
                        StartupLog.log(ctx, "Mo man hinh cap quyen LOI: " + e);
                    }
                }
                JSONObject o = new JSONObject();
                o.put("enabled", QrOverlayService.isEnabled(ctx));
                o.put("canShow", QrOverlayService.canShow(ctx));
                sendJson(out, o);
                return;
            }
            case "/api/resolve": {
                String id = q.get("id");
                if (id == null) { sendJson(out, new JSONObject().put("error", "no id")); return; }
                sendJson(out, new JSONObject(new MesuResolver().resolve(id)));
                return;
            }
            case "/api/update": {
                // v1.0.27: self-update control — toggle only. The check /
                // download / install prompt all run inside UpdateChecker
                // (service start + ~6 h watchdog cadence); the panel just
                // flips the auto_update flag (default ON) and reads status.
                String action = q.get("action");
                if ("toggle".equals(action)) {
                    boolean on = !UpdateChecker.isAutoUpdateEnabled(ctx);
                    UpdateChecker.setAutoUpdateEnabled(ctx, on);
                    StartupLog.log(ctx, "Tu cap nhat: " + (on ? "BAT" : "TAT"));
                }
                JSONObject o = new JSONObject();
                UpdateChecker u = service == null ? null : service.getUpdateChecker();
                o.put("autoUpdate", UpdateChecker.isAutoUpdateEnabled(ctx));
                o.put("updateAvailable", u != null && u.isAvailable());
                o.put("updateVersion", u == null ? "" : u.getRemoteVersionName());
                o.put("updateState", u == null ? "none" : u.getState());
                sendJson(out, o);
                return;
            }
            case "/stream": {
                proxyStream(q.get("u"), headers, out);
                return;
            }
            default:
                send(out, 404, "text/plain", "not found".getBytes("UTF-8"));
        }
    }

    /** Proxy a remote media URL (manifests / .m3u8 / googlevideo) with Range passthrough. */
    private void proxyStream(String urlStr, Map<String, String> headers, OutputStream out) throws Exception {
        if (urlStr == null || urlStr.isEmpty()) {
            send(out, 400, "text/plain", "missing u".getBytes("UTF-8"));
            return;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", YoutubeApi.UA);
        String range = headers.get("range");
        if (range != null) c.setRequestProperty("Range", range);
        int code = c.getResponseCode();
        String ct = c.getContentType();
        if (ct == null) ct = "application/octet-stream";
        long len = c.getContentLengthLong();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        head.append("Content-Type: ").append(ct).append("\r\n");
        if (len >= 0) head.append("Content-Length: ").append(len).append("\r\n");
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) head.append("Content-Range: ").append(cr).append("\r\n");
        head.append("Accept-Ranges: bytes\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        out.flush();
    }

    // ================= embedded pages =================

    private static final String COMMON_CSS =
        "body{background:#09090d;color:#f5f5f5;font-family:sans-serif;margin:0;padding:16px;font-size:22px}" +
        "h1{font-size:30px;margin:8px 0}h2{font-size:26px;margin:18px 0 8px}" +
        "button,input{font-size:24px;padding:12px 18px;margin:6px;border-radius:10px;border:1px solid #555;background:#1c1c22;color:#fff}" +
        "button:focus,input:focus{outline:3px solid #d9a441}button{background:#2a2a33}" +
        ".row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}" +
        ".item{padding:12px;border-bottom:1px solid #333;cursor:pointer}.item:hover{background:#1c1c22}" +
        ".item img{width:160px;vertical-align:middle;margin-right:12px}" +
        ".muted{color:#aaa;font-size:20px}.url{word-break:break-all;color:#7dd3fc}" +
        "#q{flex:1;min-width:280px}";

    /** Phone control panel (served at / and /panel).
     * v1.0.29: the REAL Gathin TV panel, ported from the original Gathin
     * tvhome APK (classes3.dex string constant), with three adaptations:
     * (1) a window.fetch shim (Pinggy Caution handshake + one retry on
     * JSON-parse failure — the v1.0.19 mechanism, same XHR header);
     * (2) a small "Hệ thống" footer card (self-update row + Bật/Tắt
     * toggle via /api/update, QR overlay Hiện/Ẩn via /api/overlay,
     * Pinggy restart via /api/pinggy) — the Gathin panel has no such
     * rows; (3) the extracted original was TRUNCATED mid-script (ends
     * "...if(s&&s.ok){toa", no </script></html>, no bPlDel handler,
     * no refresh() init) — the tail was reconstructed in the
     * original's style (bPlEnqAll toast, bPlDel handler, refresh() +
     * 5 s poll init), and one extraction artifact fixed (6 U+FFFD
     * chars in the pasteLink toast). Channel search/view stays
     * UI-only (server returns empty results / ok:false). */
    private static final String PAGE =
        "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Karaoke · Điều khiển từ xa</title><style>*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:#0c0c12;color:#f2f2f7}.wrap{max-width:640px;margin:0 auto;padding:10px 6px 32px}h1{font-size:19px;margin:2px 0 12px}h2{font-size:14px;color:#b9b9c7;margin:16px 0 6px;text-transform:uppercase;letter-spacing:.06em}.card{background:#17171f;border-radius:16px;padding:10px;display:flex;gap:10px;align-items:center}.card img{width:84px;height:47px;object-fit:cover;border-radius:10px;background:#26262f}.t{font-weight:700;font-size:15px;line-height:1.3}.a{color:#b9b9c7;font-size:13px;margin-top:3px}.ctrl{display:flex;gap:8px;justify-content:center;margin-top:10px}.ctrl button{width:50px;height:50px;border-radius:50%;border:1px solid #34343f;background:#1e1e28;color:#fff;font-size:19px}.ctrl button.main{width:60px;height:60px;background:#ff3b57;border-color:#ff3b57;font-size:22px}.prog{text-align:center;color:#b9b9c7;font-size:12.5px;margin-top:6px}.volrow{display:flex;align-items:center;gap:10px;background:transparent;border-radius:0;padding:8px 2px;margin-top:6px;font-size:15px}.volrow input{flex:1;accent-color:#ff3b57;height:28px}.volrow .vv{color:#b9b9c7;font-size:13px;min-width:38px;text-align:right}.sleeprow{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:2px;background:transparent;border-radius:0;padding:6px 2px}.sleeprow .lbl{font-size:13.5px;color:#b9b9c7;margin-right:2px}.sleeprow button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:10px;padding:8px 12px;font-size:13px}.sleeprow button.on{background:#ff3b57;border-color:#ff3b57;font-weight:700}.search{display:flex;gap:8px}.search input{flex:1;padding:12px 14px;border-radius:12px;border:1px solid #34343f;background:#17171f;color:#fff;font-size:15px}.search button{padding:0 18px;border-radius:12px;border:0;background:#ff3b57;color:#fff;font-size:15px;font-weight:700}.row{display:flex;gap:8px;align-items:center;background:transparent;border-radius:0;padding:8px 2px;margin-top:0;border-bottom:1px solid #1e1e28}.row img{width:76px;height:43px;object-fit:cover;border-radius:8px;background:#26262f}.row .inf{flex:1;min-width:0}.row .t{font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row .a{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:50%;width:38px;height:38px;padding:0;font-size:14px;white-space:nowrap;display:inline-flex;align-items:center;justify-content:center;flex:none}.row button.go{background:#ff3b57;border-color:#ff3b57;font-weight:700}.row.now{outline:2px solid #ff3b57;outline-offset:-2px;border-radius:10px}.empty{color:#8a8a99;font-size:13.5px;padding:6px 2px}.tabs{position:sticky;top:0;z-index:5;display:flex;gap:8px;padding:10px 0 6px;background:#0c0c12}.tabs button{flex:1;padding:11px 6px;border-radius:12px;border:1px solid #34343f;background:#17171f;color:#b9b9c7;font-size:14px;font-weight:700;white-space:nowrap}.tabs button.on{background:#ff3b57;border-color:#ff3b57;color:#fff}.paste{display:flex;gap:8px;margin-top:8px}.paste input{flex:1;min-width:0;padding:12px 14px;border-radius:12px;border:1px solid #34343f;background:#17171f;color:#fff;font-size:14px}.paste button{flex:1;padding:11px 14px;border-radius:12px;border:1px solid #34343f;background:#1e1e28;color:#fff;font-size:14px;white-space:nowrap}.paste button.go{background:#ff3b57;border-color:#ff3b57;font-weight:700}.more{display:block;width:100%;margin-top:10px;padding:12px;border-radius:12px;border:1px solid #34343f;background:#1e1e28;color:#fff;font-size:14px}.note{color:#9a9aad;font-size:13px;margin-top:8px;min-height:15px}.drag{cursor:grab;touch-action:none;color:#666672;font-size:15px;line-height:1;padding:0 0 0 1px;user-select:none;-webkit-user-select:none}.row.dragging{opacity:.94;box-shadow:0 10px 2" +
        "4px rgba(0,0,0,.55);z-index:3;position:relative}.marq{overflow:hidden}.marq .mqin{display:inline-block;white-space:nowrap}.marq i{font-style:normal;display:inline-block}.marq .mq2{display:none;margin-left:44px}.marq.run .mq2{display:inline-block}.marq.run .mqin{animation:marq var(--marq-d,8s) linear infinite}@keyframes marq{0%,10%{transform:translateX(0)}100%{transform:translateX(calc(-1*var(--marq-x,0px)))}}.row button svg{width:19px;height:19px;display:block}.drag svg{width:15px;height:15px;display:block}.tabs button svg{width:15px;height:15px;vertical-align:-2px;margin-right:5px}h1 svg{width:21px;height:21px;vertical-align:-3px}.ctrl button{display:inline-flex;align-items:center;justify-content:center}.ctrl button svg{width:23px;height:23px;display:block}.ctrl button.main svg{width:26px;height:26px}.volrow span svg{width:20px;height:20px;display:block}.sleeprow .lbl svg{width:15px;height:15px;vertical-align:-2px;margin-right:2px}.search button svg{width:19px;height:19px;display:block}.row{touch-action:pan-y}.swrap{position:relative;overflow:hidden;border-bottom:1px solid #1e1e28}.swrap .row{border-bottom:none;background:#0c0c12;position:relative;z-index:1}.swlab{position:absolute;top:0;bottom:0;left:0;right:0;display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:700;opacity:0;pointer-events:none;padding:0 16px}.swlab svg{width:18px;height:18px;flex:none}.swl{justify-content:flex-start;color:#ff8fa3;background:rgba(255,59,87,.16)}.swr{justify-content:flex-end;color:#b3adff;background:rgba(120,110,255,.16)}.swrap.now{outline:2px solid #ff3b57;outline-offset:-2px;border-radius:10px}.swrap.dragging{opacity:.94;box-shadow:0 10px 24px rgba(0,0,0,.55);z-index:3}.modes{display:flex;gap:8px;margin-top:8px}.modes button{flex:1;padding:8px;border-radius:10px;border:1px solid #34343f;background:#17171f;color:#b9b9c7;font-size:13.5px;font-weight:700}.modes button.on{background:#ff3b57;border-color:#ff3b57;color:#fff}#chBar{align-items:center;gap:10px;margin-top:10px}#chBar button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:10px;padding:8px 12px;font-size:13.5px;flex:none}#chName{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.sheet{position:fixed;left:0;right:0;bottom:0;background:#1d1d27;border-top:1px solid #33333f;border-radius:16px 16px 0 0;padding:14px 16px calc(14px + env(safe-area-inset-bottom));z-index:60;display:none;max-height:60vh;overflow:auto}.sheet.open{display:block}.sheet h3{margin:2px 0 10px;font-size:16px}.plrow{display:block;width:100%;text-align:left;background:#26262f;color:#fff;border:1px solid #383844;border-radius:10px;padding:11px 12px;font-size:15px;margin:0 0 8px;cursor:pointer;font-family:inherit}.plbar{display:flex;align-items:center;gap:10px;margin-top:10px}.plbar button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:10px;padding:8px 12px;font-size:13.5px;flex:none}.plbar span{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}#toast{position:fixed;left:50%;transform:translateX(-50%);bottom:22px;background:#26262f;border:1px solid #3a3a48;color:#fff;padding:9px 14px;border-radius:999px;font-size:14px;z-index:70;display:none;max-width:88vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}</style></head><body><div class=\"wrap\"><h1><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 16v-3.2a8 8 0 0 1 16 0V16\"/><rect x=\"3\" y=\"14.5\" width=\"4.4\" height=\"6.4\" rx=\"2.2\"/><rect x=\"16.6\" y=\"14.5\" width=\"4.4\" height=\"6.4\" rx=\"2.2\"/></svg> Karaoke · Điều khiển từ xa</h1><div class=\"card\"><img id=\"npImg\" alt=\"\"><div class=\"inf\" style=\"flex:1;min-width:0\"><div class=\"t marq\" id=\"npTitle\">Chưa phát bài nào</div><div class=\"a\" id=\"npArtist\">Tìm bài bên dưới rồi bấm Phát nhé</div></div></div><div class=\"ctrl\"><button id=\"bPrev\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M18 6L9.5 12 1" +
        "8 18z\"/><rect x=\"5.7\" y=\"6\" width=\"2.8\" height=\"12\" rx=\"1\"/></svg></button><button id=\"bPlay\" class=\"main\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M8 5.5v13l11-6.5z\"/></svg></button><button id=\"bNext\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M6 6l8.5 6L6 18z\"/><rect x=\"15.5\" y=\"6\" width=\"2.8\" height=\"12\" rx=\"1\"/></svg></button></div><div class=\"prog\" id=\"npProg\"></div><div class=\"volrow\"><span><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 9.5v5h3.5L13 19V5L7.5 9.5H4z\" fill=\"currentColor\" stroke=\"none\"/><path d=\"M15.5 9.2a4 4 0 0 1 0 5.6M18 6.8a7.4 7.4 0 0 1 0 10.4\"/></svg></span><input type=\"range\" id=\"vol\" min=\"0\" max=\"100\" step=\"1\" value=\"0\"><span class=\"vv\" id=\"volVal\"></span></div><div class=\"sleeprow\"><span class=\"lbl\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M20 14.2A8.2 8.2 0 1 1 9.8 4a6.6 6.6 0 0 0 10.2 10.2z\"/></svg> Hẹn giờ ngủ <span id=\"sleepState\"></span></span><button data-sleep=\"15\">15p</button><button data-sleep=\"30\">30p</button><button data-sleep=\"60\">60p</button><button data-sleep=\"0\">Tắt</button></div><div class=\"tabs\"><button id=\"tabBtnSearch\" class=\"on\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\"><circle cx=\"11\" cy=\"11\" r=\"6.5\"/><path d=\"M16 16l4.5 4.5\"/></svg> Tìm bài</button><button id=\"tabBtnQueue\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg> Hàng chờ</button><button id=\"tabBtnPl\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"7\" cy=\"18\" r=\"2.6\"/><circle cx=\"17.5\" cy=\"16\" r=\"2.6\"/><path d=\"M9.6 18V7.5L20.1 6v10\"/></svg> Playlist</button></div><div id=\"tabSearch\"><div class=\"search\"><input id=\"q\" placeholder=\"Tên bài hát… hoặc dán link YouTube\" enterkeyhint=\"search\"><button id=\"bMic\" title=\"Tìm bằng giọng nói\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"2.8\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3.2\"/></svg></button><button id=\"bSearch\">Tìm</button></div><div class=\"modes\"><button id=\"mVideos\" class=\"on\">Bài hát</button><button id=\"mChannels\">Kênh</button></div><div id=\"chBar\" style=\"display:none\"><button id=\"chBack\">&#8592; Kết quả tìm kiếm</button><span id=\"chName\"></span></div><div class=\"paste\" id=\"linkRow\" style=\"display:none\"><button id=\"bPastePlay\" class=\"go\">Phát ngay</button><button id=\"bPasteQueue\">+ Cho vào hàng chờ</button></div><div class=\"note\" id=\"pasteNote\"></div><div id=\"results\"></div><button class=\"more\" id=\"bMore\" style=\"display:none\">Xem thêm kết quả</button><div id=\"moreSentinel\"></div></div><div id=\"tabQueue\" style=\"display:none\"><div id=\"queue\"></div></div><div id=\"tabPl\" style=\"display:none\"><div id=\"plHome\"><div id=\"plists\"></div><button class=\"more\" id=\"bPlNew\">+ Tạo playlist mới</button></div><div id=\"plDetail\" style=\"display:none\"><div class=\"plbar\"><button id=\"plBack\">&#8592; Danh sách</button><span id=\"plName\"></span></div><div class=\"paste\"><button id=\"bPlPlayAll\" class=\"go\">Phát tất cả</button><button id=\"bPlEnqAll\">+ Hàng chờ</button><button id=\"bPlDel\">Xoá playlist</button></div><div id=\"plTracks\" style=\"margin-top:6px\"></div></div></div><h2>Hệ thống</h2><div class=\"sleeprow\"><span class=\"lbl\">Cập nhật <span id=\"upd\"></span></span><button id=\"updTgl\">Bật/Tắt tự cập nhật</button></div><div class=\"sleeprow\"><span class=\"lbl\">QR nổi · Pinggy</span><button id=\"ovOn\">Hiện QR</button><button id=\"ovOff\">Ẩn QR</button><button id=\"pgRe\">Đổi link</button></div><div class=\"sheet\" id=\"plSheet\"><h3>Thêm vào playlist<" +
        "/h3><div id=\"plList\"></div><button class=\"plrow\" id=\"plClose\">Đóng</button></div><div id=\"toast\"></div><script>/* v1.0.29: Pinggy free chen trang Caution truoc moi request tu trinh duyet (ke ca /api/*) - fetch() nhan ve HTML, r.json() nem loi va panel chet (loi v1.0.19). Shim nay boc window.fetch mot cho: neu body khong parse duoc JSON thi goi handshake (XHR GET + header X-Pinggy-No-Screen-Cookie + withCredentials) roi thu lai request mot lan. Moi fetch/post cua panel deu di qua day. */if(window.fetch){(function(){var _f=window.fetch.bind(window);function hs(){try{var x=new XMLHttpRequest();x.open('GET',location.href,true);x.withCredentials=true;x.setRequestHeader('X-Pinggy-No-Screen-Cookie','true');x.send()}catch(e){}}window.fetch=function(u,o){return _f(u,o).then(function(r){return r.clone().text().then(function(t){try{JSON.parse(t);return r}catch(e){hs();return new Promise(function(res){setTimeout(res,1500)}).then(function(){return _f(u,o)})}})});};hs()})();}var $=function(id){return document.getElementById(id)};function fmt(s){s=Math.max(0,s|0);var h=(s/3600)|0,m=((s%3600)/60)|0,x=s%60;return (h?h+':':'')+((h&&m<10)?'0':'')+m+':'+(x<10?'0':'')+x}function esc(t){var d=document.createElement('div');d.textContent=t||'';return d.innerHTML}function post(path,data){var b=new URLSearchParams(data||{});return fetch(path,{method:'POST',body:b}).then(function(r){return r.json()})}function itemRow(it,actions,opts){var d=document.createElement('div');d.className='row';d.innerHTML=(opts&&opts.drag?'<div class=\"drag\">'+IC_DRAG+'</div>':'')+'<img src=\"'+esc(it.thumb)+'\"><div class=\"inf\"><div class=\"t marq\"><span class=\"mqin\"><i>'+esc(it.title)+'</i><i class=\"mq2\">'+esc(it.title)+'</i></span></div><div class=\"a\">'+esc(it.artist)+(it.duration?' · '+fmt(it.duration):'')+'</div></div>';actions.forEach(function(a){var btn=document.createElement('button');if(a.icon)btn.innerHTML=a.icon;else btn.textContent=a.label;if(a.label)btn.title=a.label;if(a.cls)btn.className=a.cls;btn.onclick=function(){a.fn()};d.appendChild(btn)});if(opts&&(opts.swipeDel||opts.swipePl)){var w=document.createElement('div');w.className='swrap';var l1=document.createElement('div');l1.className='swlab swl';l1.innerHTML=IC_TRASH+'<span>Xoá</span>';var l2=document.createElement('div');l2.className='swlab swr';l2.innerHTML=IC_PL+'<span>Thêm playlist</span>';if(!opts.swipeDel)l1.style.display='none';if(!opts.swipePl)l2.style.display='none';w.appendChild(l1);w.appendChild(l2);w.appendChild(d);addSwipe(d,it,opts,l1,l2);return w}return d}function addSwipe(d,it,opts,L,R){var sx=0,sy=0,dx=0,mode=0,act=false;d.addEventListener('pointerdown',function(e){if(e.target.closest('button')||e.target.closest('.drag'))return;sx=e.clientX;sy=e.clientY;dx=0;mode=0;act=true;swiping=true;d.style.transition='';try{d.setPointerCapture(e.pointerId)}catch(_){}});d.addEventListener('pointermove',function(e){if(!act)return;var mx=e.clientX-sx,my=e.clientY-sy;if(!mode){if(Math.abs(mx)>12&&Math.abs(mx)>Math.abs(my))mode=1;else if(Math.abs(my)>12){act=false;swiping=false;return}}if(mode===1){dx=mx;d.style.transform='translateX('+dx+'px)';if(L)L.style.opacity=dx>0?Math.min(1,dx/84):0;if(R)R.style.opacity=dx<0?Math.min(1,-dx/84):0}});function end(){swiping=false;if(!act)return;act=false;d.style.transition='transform .18s';if(mode===1&&dx>84&&opts.swipeDel){var idx=+(d.closest('.swrap')||d).dataset.idx;d.style.transform='translateX(105%)';setTimeout(function(){post('/api/control',{action:'remove',index:idx}).then(refresh)},170)}else if(mode===1&&dx<-84&&opts.swipePl){openPl(it);d.style.transform=''}else d.style.transform='';if(L)L.style.opacity=0;if(R)R.style.opacity=0;dx=0;mode=0}d.addEventListener('pointerup',end);d.addEventListener('pointercancel',end)}function refresh(){fetch('/api/state').then(function(r){return r.json()}).then(function(s){var c=s.current;if(c){if(c.title!==lastNp){lastNp=c.title;$('npTitle').innerHTML='<span class=\"mqin\"><i>'+esc(c.title)+'</i><i class=\"mq2\">'+esc(c.title)+'</i></span>" +
        "'}$('npArtist').textContent=c.artist||'';$('npImg').src=c.thumb||'';$('npProg').textContent=fmt(s.position)+' / '+fmt(s.duration||c.duration)}else{$('npTitle').textContent='Chưa phát bài nào';$('npProg').textContent=''}$('bPlay').innerHTML=s.playing?IC_PAUSE:IC_PLAY;if(!volDrag&&s.volume>=0){$('vol').value=s.volume;$('volVal').textContent=s.volume+'%'}var sl=s.sleepLeft||0;$('sleepState').textContent=sl>0?'(còn '+sl+' phút)':'';document.querySelectorAll('[data-sleep]').forEach(function(b){var m=+b.getAttribute('data-sleep');b.className=(sl>0&&m>0&&Math.abs(m-sl)<=1)||(sl===0&&m===0)?'on':''});$('tabBtnQueue').innerHTML=IC_QTAB+' Hàng chờ · '+(s.queue?s.queue.length:0);if(!qDrag&&!swiping){var q=$('queue');q.innerHTML='';if(!s.queue||!s.queue.length){q.innerHTML='<div class=\"empty\">Hàng chờ đang trống.</div>'}s.queue&&s.queue.forEach(function(it,i){var row=itemRow(it,[{icon:IC_PLAY,label:i===s.index?'Đang phát':'Phát',cls:i===s.index?'go':'',fn:function(){post('/api/control',{action:'jump',index:i}).then(refresh)}}],{drag:1,swipeDel:1,swipePl:1});row.dataset.idx=i;if(i===s.index)row.className+=' now';q.appendChild(row)})}fixTitles(document)}).catch(function(){})}var curQ='',curPage=0,searchDone=false,searchBusy=false,lastNp='',seenIds={};var searchType='video',chView=null,chCont=null;function fixTitles(root){(root||document).querySelectorAll('.marq').forEach(function(el){var inner=el.firstElementChild;if(!inner||!inner.children||!inner.children[0])return;var w=inner.children[0].offsetWidth;if(w-el.clientWidth>6){var shift=w+44;var d=Math.min(22,Math.max(4,shift/45));el.style.setProperty('--marq-x',shift+'px');el.style.setProperty('--marq-d',d+'s');el.classList.add('run');inner.style.animationDelay=(-((Date.now()/1000)%d)).toFixed(2)+'s'}else el.classList.remove('run')})}function showTab(w){$('tabSearch').style.display=w==='search'?'':'none';$('tabQueue').style.display=w==='queue'?'':'none';$('tabPl').style.display=w==='pl'?'':'none';$('tabBtnSearch').className=w==='search'?'on':'';$('tabBtnQueue').className=w==='queue'?'on':'';$('tabBtnPl').className=w==='pl'?'on':'';if(w==='pl'){if(plOpenId)openPlist(plOpenId,plOpenName);else loadPlists()}}$('tabBtnSearch').onclick=function(){showTab('search')};$('tabBtnQueue').onclick=function(){showTab('queue')};$('tabBtnPl').onclick=function(){showTab('pl')};function resultRow(it){return itemRow(it,[{icon:IC_PLAY,label:'Phát',cls:'go',fn:function(){post('/api/playnow',it).then(refresh)}},{icon:IC_ENQ,label:'+ Chờ',fn:function(){post('/api/enqueue',it).then(refresh)}}],{swipePl:1})}function loadPage(){if(chView){loadChannelPage(false);return}if(searchBusy||searchDone||!curQ)return;searchBusy=true;var next=curPage+1;if(next>1)$('bMore').textContent='Đang tải thêm…';fetch('/api/search?q='+encodeURIComponent(curQ)+'&page='+next).then(function(r){return r.json()}).then(function(s){searchBusy=false;if(!s.ok){searchDone=true;$('bMore').style.display='none';if(curPage===0)$('results').innerHTML='<div class=\"empty\">'+(esc(s.error)||'Không tìm được.')+'</div>';return}curPage=next;var box=$('results');if(curPage===1)box.innerHTML='';if(!s.results.length){searchDone=true;if(curPage===1)box.innerHTML='<div class=\"empty\">Không thấy kết quả nào.</div>'}s.results.forEach(function(it){if(seenIds[it.id])return;seenIds[it.id]=1;box.appendChild(resultRow(it))});fixTitles(box);if(s.results.length<20)searchDone=true;$('bMore').style.display=searchDone?'none':'';$('bMore').textContent='Xem thêm kết quả'}).catch(function(){searchBusy=false;$('bMore').textContent='Xem thêm kết quả';if(curPage===0)$('results').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}function extractId(t){var m=t.match(/(?:youtube\\.com\\/(?:watch\\?[^\\s]*v=|shorts\\/|embed\\/|live\\/)|youtu\\.be\\/)([A-Za-z0-9_-]{11})/);if(m)return m[1];return /^[A-Za-z0-9_-]{11}$/.test(t)?t:null}function doSearch(){var q=$('q').value.trim();if(!q)return;if(extractId(q)){pasteLink(q,'now');return}chView=null;chCont=null;$('chBar').style.display='non" +
        "e';if(searchType==='channel'){curQ=q;curPage=0;searchDone=true;searchBusy=false;$('results').innerHTML='<div class=\"empty\">Đang tìm kênh…</div>';$('bMore').style.display='none';fetch('/api/search?q='+encodeURIComponent(q)+'&type=channel').then(function(r){return r.json()}).then(function(s){var box=$('results');box.innerHTML='';if(!s.ok||!s.results||!s.results.length){box.innerHTML='<div class=\"empty\">Không thấy kênh nào.</div>';return}s.results.forEach(function(it){box.appendChild(channelRow(it))});fixTitles(box)}).catch(function(){$('results').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'});return}curQ=q;curPage=0;searchDone=false;seenIds={};$('results').innerHTML='<div class=\"empty\">Đang tìm…</div>';$('bMore').style.display='none';loadPage()}function pasteLink(u,mode){var note=$('pasteNote');note.textContent='Đang lấy thông tin video…';post('/api/paste',{url:u,mode:mode}).then(function(s){if(s.ok&&s.item){note.textContent=(mode==='now'?'▶ Đang phát: ':' Đã thêm vào hàng chờ: ')+s.item.title;refresh()}else note.textContent=s.error||'Không xử lý được link này.'}).catch(function(){note.textContent='Mất kết nối tới thiết bị.'})}$('bPastePlay').onclick=function(){var u=$('q').value.trim();if(u){pasteLink(u,'now');$('q').value='';$('linkRow').style.display='none'}};$('bPasteQueue').onclick=function(){var u=$('q').value.trim();if(u){pasteLink(u,'queue');$('q').value='';$('linkRow').style.display='none'}};$('q').addEventListener('input',function(){$('linkRow').style.display=extractId($('q').value.trim())?'':'none'});$('bMore').onclick=loadPage;var scrollTick=false;window.addEventListener('scroll',function(){if(scrollTick)return;scrollTick=true;setTimeout(function(){scrollTick=false;var el=$('moreSentinel');if(!el)return;var r=el.getBoundingClientRect();if(r.top<window.innerHeight+600&&r.bottom>0)loadPage()},160)},{passive:true});if(window.IntersectionObserver){new IntersectionObserver(function(e){if(e[0].isIntersecting)loadPage()},{rootMargin:'400px'}).observe($('moreSentinel'))}function channelRow(it){return itemRow(it,[{icon:IC_QTAB,label:'Xem video của kênh',cls:'go',fn:function(){openChannelView(it)}}])}function openChannelView(ch){chView=ch;chCont=null;seenIds={};searchDone=false;searchBusy=false;$('chName').textContent=ch.title;$('chBar').style.display='flex';$('results').innerHTML='<div class=\"empty\">Đang lấy video của kênh…</div>';$('bMore').style.display='none';loadChannelPage(true)}function loadChannelPage(first){if(!chView||searchBusy||(!first&&searchDone))return;searchBusy=true;fetch('/api/channel?id='+encodeURIComponent(chView.id)+(chCont?'&continuation='+encodeURIComponent(chCont):'')).then(function(r){return r.json()}).then(function(s){searchBusy=false;var box=$('results');if(first)box.innerHTML='';if(!s.ok){if(first)box.innerHTML='<div class=\"empty\">Không lấy được video của kênh.</div>';searchDone=true;$('bMore').style.display='none';return}if(first&&!s.results.length)box.innerHTML='<div class=\"empty\">Kênh chưa có video nào.</div>';s.results.forEach(function(it){if(seenIds[it.id])return;seenIds[it.id]=1;box.appendChild(resultRow(it))});fixTitles(box);chCont=s.continuation||null;searchDone=!chCont;$('bMore').style.display=searchDone?'none':'';$('bMore').textContent='Xem thêm video của kênh'}).catch(function(){searchBusy=false;if(first)$('results').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}$('chBack').onclick=function(){chView=null;chCont=null;$('chBar').style.display='none';doSearch()};$('mVideos').onclick=function(){if(searchType==='video')return;searchType='video';$('mVideos').className='on';$('mChannels').className='';chView=null;$('chBar').style.display='none';doSearch()};$('mChannels').onclick=function(){if(searchType==='channel')return;searchType='channel';$('mChannels').className='on';$('mVideos').className='';chView=null;$('chBar').style.display='none';doSearch()};$('bSearch').onclick=doSearch;$('q').addEventListener('keydown',function(e){if(e.key==='Enter')doSearch()});" +
        "$('bPlay').onclick=function(){post('/api/control',{action:'toggle'}).then(refresh)};$('bNext').onclick=function(){post('/api/control',{action:'next'}).then(refresh)};$('bPrev').onclick=function(){post('/api/control',{action:'prev'}).then(refresh)};var volDrag=false,volTimer=null;$('vol').addEventListener('input',function(){volDrag=true;$('volVal').textContent=$('vol').value+'%';clearTimeout(volTimer);volTimer=setTimeout(function(){post('/api/control',{action:'volume',value:$('vol').value}).then(function(){volDrag=false;refresh()})},180)});document.querySelectorAll('[data-sleep]').forEach(function(b){b.onclick=function(){post('/api/control',{action:'sleep',minutes:b.getAttribute('data-sleep')}).then(refresh)}});var SR=window.SpeechRecognition||window.webkitSpeechRecognition;var micBusy=false;function micFallback(){$('q').focus();alert('Trình duyệt này không cho trang web dùng mic trực tiếp. Cách nói để tìm: chạm vào ô tìm kiếm rồi bấm nút mic trên bàn phím điện thoại (iPhone và Android đều có).')};$('bMic').onclick=function(){if(!SR){micFallback();return}if(micBusy)return;micBusy=true;try{var r=new SR();r.lang='vi-VN';r.continuous=false;r.interimResults=false;r.maxAlternatives=1;r.onresult=function(e){$('q').value=e.results[0][0].transcript;doSearch()};r.onend=function(){micBusy=false};r.onerror=function(ev){micBusy=false;if(ev.error==='not-allowed'||ev.error==='service-not-allowed'||ev.error==='audio-capture')micFallback()};r.start()}catch(_){micBusy=false;micFallback()}};var IC_PLAY='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M8 5.5v13l11-6.5z\"/></svg>',IC_PAUSE='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><rect x=\"6.5\" y=\"5\" width=\"3.6\" height=\"14\" rx=\"1.2\"/><rect x=\"13.9\" y=\"5\" width=\"3.6\" height=\"14\" rx=\"1.2\"/></svg>',IC_QTAB='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg>',IC_ENQ='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg>',IC_PL='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h10M4 12h10M4 17.5h6\"/><path d=\"M17.8 14.8v6.4M14.6 18h6.4\"/></svg>',IC_TRASH='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 7h16M9 7V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v2M6.5 7l1 12A1.5 1.5 0 0 0 9 20.5h6a1.5 1.5 0 0 0 1.5-1.5l1-12M10 11v6M14 11v6\"/></svg>',IC_DRAG='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><circle cx=\"9\" cy=\"6.5\" r=\"1.7\"/><circle cx=\"15\" cy=\"6.5\" r=\"1.7\"/><circle cx=\"9\" cy=\"12\" r=\"1.7\"/><circle cx=\"15\" cy=\"12\" r=\"1.7\"/><circle cx=\"9\" cy=\"17.5\" r=\"1.7\"/><circle cx=\"15\" cy=\"17.5\" r=\"1.7\"/></svg>';var plItem=null,toastTimer=0;function toast(m){var t=$('toast');t.textContent=m;t.style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(function(){t.style.display='none'},2600)}function openPl(it){plItem=it;$('plList').innerHTML='<div class=\"note\">Đang tải…</div>';$('plSheet').classList.add('open');fetch('/api/playlists').then(function(r){return r.json()}).then(function(s){var box=$('plList');box.innerHTML='';(s.playlists||[]).forEach(function(pl){var b=document.createElement('button');b.className='plrow';b.textContent=pl.name+' · '+pl.count+' bài';b.onclick=function(){addToPl(pl.id,'')};box.appendChild(b)});var nb=document.createElement('button');nb.className='plrow';nb.textContent='＋ Tạo playlist mới';nb.onclick=function(){var n=prompt('Tên playlist mới:');if(n)addToPl('new',n)};box.appendChild(nb)}).catch(function(){$('plList').innerHTML='<div class=\"note\">Không tải được danh sách playlist.</div>'})}function addToPl(pid,name){if(!plIt" +
        "em)return;post('/api/playlist-add',{playlist:pid,name:name,id:plItem.id,title:plItem.title,artist:plItem.artist,thumb:plItem.thumb,duration:plItem.duration}).then(function(s){$('plSheet').classList.remove('open');toast(s&&s.ok?(s.added>0?'Đã thêm vào “'+s.name+'”':'Bài đã có trong “'+s.name+'”'):'Không thêm được')})}$('plClose').onclick=function(){$('plSheet').classList.remove('open')};var qDrag=null,swiping=false;$('queue').addEventListener('pointerdown',function(e){var h=e.target.closest?e.target.closest('.drag'):null;if(!h)return;var row=h.closest('.swrap')||h.parentElement;e.preventDefault();try{h.setPointerCapture(e.pointerId)}catch(_){}qDrag={row:row,from:+row.dataset.idx,y0:e.clientY};row.classList.add('dragging')});document.addEventListener('pointermove',function(e){if(!qDrag)return;qDrag.row.style.transform='translateY('+(e.clientY-qDrag.y0)+'px)'});document.addEventListener('pointerup',function(e){if(!qDrag)return;var row=qDrag.row,from=qDrag.from;row.style.transform='';row.classList.remove('dragging');var rows=[].slice.call($('queue').children).filter(function(r){return r!==row});var to=0;rows.forEach(function(r){var b=r.getBoundingClientRect();if(e.clientY>b.top+b.height/2)to++});qDrag=null;if(to!==from)post('/api/control',{action:'move',from:from,to:to}).then(refresh);else refresh()});document.addEventListener('pointercancel',function(){if(qDrag){qDrag.row.style.transform='';qDrag.row.classList.remove('dragging');qDrag=null;refresh()}});var plOpenId=null,plOpenName='';function loadPlists(){fetch('/api/playlists').then(function(r){return r.json()}).then(function(s){var box=$('plists');box.innerHTML='';var arr=s.playlists||[];if(!arr.length)box.innerHTML='<div class=\"empty\">Chưa có playlist nào. Bấm nút bên dưới để tạo mới, hoặc vuốt trái một bài rồi thêm vào playlist.</div>';arr.forEach(function(pl){var b=document.createElement('button');b.className='plrow';b.textContent=pl.name+' · '+pl.count+' bài';b.onclick=function(){openPlist(pl.id,pl.name)};box.appendChild(b)});fixTitles(box)}).catch(function(){$('plists').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}function openPlist(id,name){plOpenId=id;plOpenName=name||'';$('plHome').style.display='none';$('plDetail').style.display='';$('plName').textContent=plOpenName;$('plTracks').innerHTML='<div class=\"empty\">Đang tải…</div>';fetch('/api/playlist?id='+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(s){if(!s.ok){$('plTracks').innerHTML='<div class=\"empty\">Không mở được playlist này.</div>';return}plOpenName=s.name;$('plName').textContent=s.name;var box=$('plTracks');box.innerHTML='';if(!s.items||!s.items.length)box.innerHTML='<div class=\"empty\">Playlist đang trống.</div>';(s.items||[]).forEach(function(it){box.appendChild(itemRow(it,[{icon:IC_PLAY,label:'Phát',cls:'go',fn:function(){post('/api/playnow',it).then(refresh)}},{icon:IC_TRASH,label:'Xoá khỏi playlist',fn:function(){post('/api/playlist-remove',{playlist:plOpenId,id:it.id}).then(function(r){if(r&&r.ok){toast('Đã xoá khỏi playlist');openPlist(plOpenId,plOpenName)}else toast('Không xoá được')})}}]))});fixTitles(box)}).catch(function(){$('plTracks').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}$('plBack').onclick=function(){plOpenId=null;$('plDetail').style.display='none';$('plHome').style.display='';loadPlists()};$('bPlNew').onclick=function(){var n=prompt('Tên playlist mới:');if(!n)return;post('/api/playlist-create',{name:n}).then(function(s){if(s&&s.ok){toast('Đã tạo playlist mới');loadPlists()}else toast('Không tạo được')})};$('bPlPlayAll').onclick=function(){if(!plOpenId)return;post('/api/playlist-play',{playlist:plOpenId,index:0}).then(function(s){if(s&&s.ok){toast('Đang phát playlist');refresh()}else toast('Playlist đang trống')})};$('bPlEnqAll').onclick=function(){if(!plOpenId)return;post('/api/playlist-enqueue',{playlist:plOpenId}).then(function(s){if(s&&s.ok){toast('Đã cho vào hàng chờ');refresh()}else toast('Không thêm được')})};$('bPlDel'" +
        ").onclick=function(){if(!plOpenId)return;if(!confirm('Xoá playlist “'+plOpenName+'”?'))return;post('/api/playlist-delete',{playlist:plOpenId,name:plOpenName}).then(function(s){if(s&&s.ok){toast('Đã xoá playlist');plOpenId=null;$('plDetail').style.display='none';$('plHome').style.display='';loadPlists()}else toast('Không xoá được')})};function updFoot(){fetch('/api/state').then(function(r){return r.json()}).then(function(s){var us=s.updateState||'none';var ut=us==='downloading'?'đang tải':us==='ready'?'đã tải xong — xem thông báo trên TV để cài đặt':us==='checking'?'đang kiểm tra':us==='error'?'lỗi, sẽ thử lại sau':us==='grant'?'cần cấp quyền cài đặt trong Cài đặt':'';$('upd').textContent=(s.autoUpdate?'Bật':'Tắt')+(s.updateAvailable?' • có bản '+s.updateVersion+(ut?' — '+ut:''):ut?' • '+ut:'')}).catch(function(){})}$('updTgl').onclick=function(){post('/api/update',{action:'toggle'}).then(updFoot)};$('ovOn').onclick=function(){post('/api/overlay',{action:'enable'}).then(updFoot)};$('ovOff').onclick=function(){post('/api/overlay',{action:'disable'}).then(updFoot)};$('pgRe').onclick=function(){post('/api/pinggy',{action:'restart'}).then(function(){setTimeout(updFoot,2500)})};refresh();updFoot();setInterval(refresh,5000);setInterval(updFoot,15000);</script></body></html>";
    /** TV stage (served at /stage): bare transparent page, exactly like the
     * original Gathin stage — zero UI on the TV, video fullscreen from the
     * SurfaceView behind the transparent WebView; all controls live on the
     * phone panel (/panel). No script: nothing on the stage needs JS. */
    private static final String STAGE_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>Karaoke YouTube</title><style>html,body{margin:0;padding:0;background:transparent;height:100%;overflow:hidden}</style></head><body></body></html>";
}
