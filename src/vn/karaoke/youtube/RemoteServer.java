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
 * v1.0.37: /api/fix (manual video fixes — refresh / force360 /
 * nextsource / restartplayer / restartapp). The v1.0.36 sign-in
 * endpoints (/api/auth/*, /api/subs*, /api/history) were REMOVED
 * at the user's ask; YouTubeAuth + resolveSignedIn() stay dormant
 * in the code so sign-in can be re-enabled later without a rebuild.
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
            service.userPlayNow(it); // v1.0.38: manual play cancels any pending backoff wait
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
                sendJson(out, new JSONObject().put("ok", true).put("count", pl.items.size()));
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
            case "/api/fix": {
                // v1.0.37: manual video-fix buttons (Settings tab,
                // "Sửa lỗi video"). Every action logs "Sua loi: ..." and
                // answers {ok, message} for the panel toast. A failed
                // action never touches the player (force360 with no
                // itag 18) or leaves it in a recoverable state
                // (restartplayer: every later play() rebuilds the
                // player via ensurePlayer()). restartapp answers FIRST
                // — PlayerService.restartApp() only schedules the kill
                // ~800 ms later so this reply flushes before the
                // process dies. NOTE: after restartapp the Pinggy
                // tunnel mints a NEW public URL, so the message tells
                // the user to re-scan the TV QR.
                String action = q.get("action");
                if (action == null) action = q.get("a");
                JSONObject o = new JSONObject();
                try {
                    if ("refresh".equals(action)) {
                        // Fresh session + fresh resolve + replay the
                        // current track (the auto 403 path does the
                        // same; this is the manual version of it).
                        try { new YoutubeApi().clearSession(); } catch (Exception ignored) { }
                        try { api.clearSession(); } catch (Exception ignored) { }
                        LibraryState.Item cur = library.current();
                        if (cur == null) {
                            o.put("ok", false).put("message", "Không có bài đang phát");
                        } else {
                            if (service != null) service.userPlayNow(cur); // v1.0.38: manual fix cancels the backoff wait
                            else player.play(cur.id, cur.title == null ? "" : cur.title);
                            o.put("ok", true).put("message", "Đã lấy link mới, đang phát lại");
                        }
                        StartupLog.log(ctx, "Sua loi: refresh " + (library.current() == null ? "khong co bai" : library.current().id));
                    } else if ("force360".equals(action)) {
                        LibraryState.Item cur = library.current();
                        if (cur == null) {
                            o.put("ok", false).put("message", "Không có bài đang phát");
                        } else {
                            // v1.0.38: full ladder — the quiet resolve
                            // returns a single VISIONOS HLS candidate and
                            // has no itag 18 to force; this button needs
                            // the full candidate list.
                            JSONObject r = new YoutubeApi().resolve(cur.id, true);
                            JSONArray cands = r.optJSONArray("candidates");
                            JSONObject itag18 = null;
                            if (cands != null) {
                                for (int i = 0; i < cands.length(); i++) {
                                    JSONObject c = cands.optJSONObject(i);
                                    if (c != null && "progressive".equals(c.optString("kind"))
                                            && "18".equals(c.optString("itag"))) { itag18 = c; break; }
                                }
                            }
                            if (itag18 == null) {
                                o.put("ok", false).put("message", "Không có bản 360p");
                            } else {
                                JSONArray one = new JSONArray();
                                one.put(itag18);
                                if (service != null) service.playCandidates(cur, one);
                                else player.playCandidates(cur.id, cur.title == null ? "" : cur.title, one);
                                o.put("ok", true).put("message", "Đang phát bản 360p");
                            }
                        }
                        StartupLog.log(ctx, "Sua loi: force360 " + (library.current() == null ? "khong co bai" : library.current().id));
                    } else if ("nextsource".equals(action)) {
                        if (service == null) {
                            o.put("ok", false).put("message", "Service chưa sẵn sàng");
                        } else if (service.nextCandidate()) {
                            o.put("ok", true).put("message", "Đã đổi nguồn phát");
                        } else {
                            o.put("ok", false).put("message", "Hết nguồn khác để thử");
                        }
                        StartupLog.log(ctx, "Sua loi: nextsource");
                    } else if ("restartplayer".equals(action)) {
                        if (service == null) {
                            o.put("ok", false).put("message", "Service chưa sẵn sàng");
                        } else if (service.restartPlayer()) {
                            o.put("ok", true).put("message", "Đã khởi động lại trình phát, đang phát lại");
                        } else {
                            o.put("ok", false).put("message", "Không có bài đang phát");
                        }
                        StartupLog.log(ctx, "Sua loi: restartplayer");
                    } else if ("restartapp".equals(action)) {
                        if (service == null) {
                            o.put("ok", false).put("message", "Service chưa sẵn sàng");
                        } else {
                            o.put("ok", true).put("message", "Đang khởi động lại ứng dụng — quét lại QR trên TV để mở bảng điều khiển mới");
                            StartupLog.log(ctx, "Sua loi: restartapp");
                            sendJson(out, o);
                            service.restartApp();
                            return;
                        }
                    } else {
                        o.put("ok", false).put("message", "Không biết hành động: " + action);
                    }
                } catch (Exception e) {
                    StartupLog.log(ctx, "Sua loi: " + action + " LOI: " + e);
                    try { o.put("ok", false).put("message", "Lỗi: " + e.getMessage()); } catch (Exception ignored) { }
                }
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
     * v1.0.32: APPROVED flat redesign (user-approved demo
     * panel-demo-flat.html, built from the real layout, not the
     * Gathin card layout): bg #0b0b10, hairline dividers #26262e,
     * accent #ff3b57, muted #a8a8b8 — NO cards anywhere. Slim header
     * ("Karaoke" + live status dot/text), now-playing bar with a
     * 72px thumb and a one-line ELLIPSIS title (no marquee — the
     * user complained titles get clipped mid-scroll), 2px progress
     * line + times, ONE row with prev/play/next + volume slider +
     * %, slim 40px search (Enter to search, mic = SpeechRecognition
     * vi-VN), flat rows (44px thumbs, ellipsis titles) separated
     * only by hairlines, and a fixed 44px bottom tab bar (Tìm bài /
     * Hàng chờ + count badge / Playlist / Cài đặt). The "Cài đặt"
     * tab holds the old "Hệ thống" footer (self-update row + toggle,
     * QR Hiện/Ẩn, Pinggy restart, LAN + public link rows).
     * REMOVED in v1.0.32: the sleep-timer row/chips + moon button
     * (panel only — PlayerService sleepCheck and /api/sleep are
     * untouched and now inert) and the "Bài hát / Kênh" mode toggle
     * + channel view (channel search is cut server-side).
     * KEPT from the v1.0.29 Gathin port: the window.fetch shim
     * (Pinggy Caution handshake + one retry), refresh() every
     * 2500 ms, updFoot() every 15 s, server-side search paging
     * (20/page, infinite scroll), queue drag-reorder + swipe-left
     * delete + swipe-right add-to-playlist, playlist bottom sheet,
     * paste row, all /api/* call sites via post(). */
private static final String PAGE =
"<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"><title>Karaoke · Điều khiển từ xa</title><style>*{box-sizing:border-box;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}input,textarea{-webkit-user-select:text;user-select:text}button{touch-action:manipulation}body{margin:0;touch-action:manipulation;font-family:system-ui,sans-serif;background:#0b0b10;color:#f2f2f7;padding-bottom:calc(44px + 16px + env(safe-area-inset-bottom))}.wrap{max-width:640px;margin:0 auto}h2{font-size:14px;color:#b9b9c7;margin:16px 16px 6px;text-transform:uppercase;letter-spacing:.06em}header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px 12px;border-bottom:1px solid #26262e}header .title{display:flex;align-items:center;gap:8px;font-size:20px;font-weight:700}header .title svg{width:22px;height:22px}header .status{color:#22c55e;font-size:13px;font-weight:600;display:flex;align-items:center;gap:5px}header .status.paused{color:#a8a8b8}header .dot{width:7px;height:7px;border-radius:50%;background:#22c55e}header .status.paused .dot{background:#8a8a98}.now{display:flex;align-items:center;gap:12px;padding:14px 16px 10px}.now img{width:72px;height:72px;object-fit:cover;border-radius:10px;background:#26262f;flex:none}.now .meta{flex:1;min-width:0}.now .t{font-size:15px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.now .a{font-size:13px;color:#a8a8b8;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.prog{padding:10px 16px;cursor:pointer;touch-action:none}.prog .bar{height:2px;background:#2c2c34;border-radius:2px;overflow:hidden}.prog .bar i{display:block;height:100%;width:0%;background:#ff3b57}.prog .time{display:flex;justify-content:space-between;color:#a8a8b8;font-size:11px;margin-top:5px}.ctl{display:flex;align-items:center;gap:8px;padding:12px 16px;border-bottom:1px solid #26262e}.ctl button{width:38px;height:38px;border-radius:50%;border:1px solid #3a3a44;background:#17171f;color:#fff;display:inline-flex;align-items:center;justify-content:center;flex:none;cursor:pointer}.ctl button.main{width:44px;height:44px;background:#ff3b57;border-color:#ff3b57}.ctl button svg{width:18px;height:18px;display:block}.vol{display:flex;align-items:center;gap:8px;flex:1;min-width:0;margin-left:6px}.vol svg{width:18px;height:18px;display:block;flex:none}.vol input{flex:1;min-width:0;accent-color:#ff3b57;height:28px}.vol .vv{font-size:13px;min-width:38px;text-align:right}.search{display:flex;gap:8px;margin:12px 16px 0}.search input{flex:1;min-width:0;padding:0 14px;height:40px;border-radius:20px;border:1px solid #2c2c34;background:#17171f;color:#fff;font-size:14px}.search input::placeholder{color:#8a8a98}.search button.mic{width:32px;height:32px;border-radius:50%;border:0;background:#ff3b57;color:#fff;display:inline-flex;align-items:center;justify-content:center;flex:none;cursor:pointer}.search button.mic svg{width:16px;height:16px;display:block}.paste{display:flex;gap:8px;margin:8px 16px 0}.paste button{flex:1;padding:11px 14px;border-radius:12px;border:1px solid #34343f;background:#1e1e28;color:#fff;font-size:14px;white-space:nowrap;cursor:pointer}.paste button.go{background:#ff3b57;border-color:#ff3b57;font-weight:700}.note{color:#9a9aad;font-size:13px;margin:8px 16px 0;min-height:15px}.list{margin-top:6px}.qbar{display:flex;align-items:center;justify-content:space-between;padding:12px 16px 0}.qbar .qt{font-size:14px;font-weight:700}.qbar button{border:1px solid #ff3b57;background:transparent;color:#ff8fa3;border-radius:10px;padding:7px 12px;font-size:13px;cursor:pointer}.row{display:flex;gap:12px;align-items:center;padding:10px 16px;border-bottom:1px solid #26262e}.row img{width:44px;height:44px;object-fit:cover;border-radius:6px;background:#26262f;flex:none}.row .inf{flex:1;min-width:0}.row .t{font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row .a{font-size:12px;color:#a8a8b8;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:50%;width:36px;height:36px;padding:0;font-size:14px;white-space:nowrap;display:inline-flex;align-items:center;justify-content:center;flex:none;cursor:pointer}.row button.go{background:#ff3b57;border-color:#ff3b57;font-weight:700}.row button svg{width:19px;height:19px;display:block}.row.now{outline:2px solid #ff3b5" +
        "7;outline-offset:-2px;border-radius:10px}.row{touch-action:pan-y}.drag{cursor:grab;touch-action:none;color:#666672;font-size:15px;line-height:1;padding:0 0 0 1px;user-select:none;-webkit-user-select:none}.drag svg{width:15px;height:15px;display:block}.row.dragging{opacity:.94;box-shadow:0 10px 24px rgba(0,0,0,.55);z-index:3;position:relative}.swrap{position:relative;overflow:hidden;border-bottom:1px solid #26262e}.swrap .row{border-bottom:none;background:#0b0b10;position:relative;z-index:1}.swlab{position:absolute;top:0;bottom:0;left:0;right:0;display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:700;opacity:0;pointer-events:none;padding:0 16px}.swlab svg{width:18px;height:18px;flex:none}.swl{justify-content:flex-start;color:#ff8fa3;background:rgba(255,59,87,.16)}.swr{justify-content:flex-end;color:#b3adff;background:rgba(120,110,255,.16)}.swrap.now{outline:2px solid #ff3b57;outline-offset:-2px;border-radius:10px}.swrap.dragging{opacity:.94;box-shadow:0 10px 24px rgba(0,0,0,.55);z-index:3}.empty{color:#8a8a99;font-size:13.5px;padding:6px 16px}.more{display:block;width:calc(100% - 32px);margin:10px 16px 0;padding:12px;border-radius:12px;border:1px solid #34343f;background:#1e1e28;color:#fff;font-size:14px;cursor:pointer}.sysrow{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:2px;padding:6px 16px}.sysrow .lbl{font-size:13.5px;color:#b9b9c7;margin-right:2px}.sysrow button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:10px;padding:8px 12px;font-size:13px;cursor:pointer}.sysrow .url{word-break:break-all;color:#7dd3fc;font-size:13px}.sheet{position:fixed;left:0;right:0;bottom:0;background:#1d1d27;border-top:1px solid #33333f;border-radius:16px 16px 0 0;padding:14px 16px calc(14px + env(safe-area-inset-bottom));z-index:60;display:none;max-height:60vh;overflow:auto}.sheet.open{display:block}.sheet h3{margin:2px 0 10px;font-size:16px}.plrow{display:block;width:100%;text-align:left;background:#26262f;color:#fff;border:1px solid #383844;border-radius:10px;padding:11px 12px;font-size:15px;margin:0 0 8px;cursor:pointer;font-family:inherit}.plbar{display:flex;align-items:center;gap:10px;margin-top:10px;padding:0 16px}.plbar button{border:1px solid #34343f;background:#1e1e28;color:#fff;border-radius:10px;padding:8px 12px;font-size:13.5px;flex:none;cursor:pointer}.plbar span{font-weight:700;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}#plists{padding:0 16px}#plTracks{padding:0 16px}#toast{position:fixed;left:50%;transform:translateX(-50%);bottom:calc(44px + 22px + env(safe-area-inset-bottom));background:#26262f;border:1px solid #3a3a48;color:#fff;padding:9px 14px;border-radius:999px;font-size:14px;z-index:70;display:none;max-width:88vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}nav{position:fixed;left:0;right:0;bottom:0;height:44px;background:#0b0b10;border-top:1px solid #26262e;z-index:40}nav .in{max-width:640px;margin:0 auto;display:flex;height:100%}nav button{flex:1;background:none;border:0;color:#8a8a98;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;position:relative;font-family:inherit;cursor:pointer;padding:0}nav button.on{color:#ff3b57}nav button svg{width:18px;height:18px;display:block}nav button .lb{font-size:10px;line-height:1;font-weight:600}nav button .bdg{position:absolute;top:3px;right:calc(50% - 22px);background:#ff3b57;color:#fff;font-size:9px;font-weight:700;min-width:15px;height:15px;border-radius:8px;display:flex;align-items:center;justify-content:center;padding:0 4px}</style></head><body><div class=\"wrap\"><header><div class=\"title\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 16v-3.2a8 8 0 0 1 16 0V16\"/><rect x=\"3\" y=\"14.5\" width=\"4.4\" height=\"6.4\" rx=\"2.2\"/><rect x=\"16.6\" y=\"14.5\" width=\"4.4\" height=\"6.4\" rx=\"2.2\"/></svg> Karaoke</div><div class=\"status paused\" id=\"hdrStatus\"><span class=\"dot\"></spa" +
        "n><span id=\"hdrStatusTxt\">Chưa phát</span></div></header><div class=\"now\"><img id=\"npImg\" alt=\"\"><div class=\"meta\"><div class=\"t\" id=\"npTitle\">Chưa phát bài nào</div><div class=\"a\" id=\"npArtist\">Tìm bài bên dưới rồi bấm Phát nhé</div></div></div><div class=\"prog\"><div class=\"bar\"><i id=\"progFill\"></i></div><div class=\"time\"><span id=\"progPos\">0:00</span><span id=\"progDur\">0:00</span></div></div><div class=\"ctl\"><button id=\"bPrev\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M18 6L9.5 12 18 18z\"/><rect x=\"5.7\" y=\"6\" width=\"2.8\" height=\"12\" rx=\"1\"/></svg></button><button id=\"bPlay\" class=\"main\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M8 5.5v13l11-6.5z\"/></svg></button><button id=\"bNext\"><svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M6 6l8.5 6L6 18z\"/><rect x=\"15.5\" y=\"6\" width=\"2.8\" height=\"12\" rx=\"1\"/></svg></button><div class=\"vol\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 9.5v5h3.5L13 19V5L7.5 9.5H4z\" fill=\"currentColor\" stroke=\"none\"/><path d=\"M15.5 9.2a4 4 0 0 1 0 5.6M18 6.8a7.4 7.4 0 0 1 0 10.4\"/></svg><input type=\"range\" id=\"vol\" min=\"0\" max=\"100\" step=\"1\" value=\"100\"><span class=\"vv\" id=\"volVal\"></span></div></div><div id=\"tabSearch\"><div class=\"search\"><input id=\"q\" placeholder=\"Tìm bài hát, kênh...\" enterkeyhint=\"search\"><button id=\"bMic\" class=\"mic\" title=\"Tìm bằng giọng nói\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"2.8\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3.2\"/></svg></button></div><div class=\"paste\" id=\"linkRow\" style=\"display:none\"><button id=\"bPastePlay\" class=\"go\">Phát ngay</button><button id=\"bPasteQueue\">+ Cho vào hàng chờ</button></div><div class=\"note\" id=\"pasteNote\"></div><div class=\"list\" id=\"results\"></div><button class=\"more\" id=\"bMore\" style=\"display:none\">Xem thêm kết quả</button><div id=\"moreSentinel\"></div></div><div id=\"tabQueue\" style=\"display:none\"><div class=\"qbar\"><span class=\"qt\">Hàng chờ</span><button id=\"bClearQueue\">Dọn sạch</button></div><div class=\"list\" id=\"queue\"></div></div><div id=\"tabPl\" style=\"display:none\"><div id=\"plHome\"><div id=\"plists\"></div><button class=\"more\" id=\"bPlNew\">+ Tạo playlist mới</button></div><div id=\"plDetail\" style=\"display:none\"><div class=\"plbar\"><button id=\"plBack\">&#8592; Danh sách</button><span id=\"plName\"></span></div><div class=\"paste\"><button id=\"bPlPlayAll\" class=\"go\">Phát tất cả</button><button id=\"bPlEnqAll\">+ Hàng chờ</button><button id=\"bPlDel\">Xoá playlist</button></div><div id=\"plTracks\" style=\"margin-top:6px\"></div></div></div><div id=\"tabSettings\" style=\"display:none\"><h2>Cài đặt</h2><div class=\"sysrow\"><span class=\"lbl\">Cập nhật <span id=\"upd\"></span></span><button id=\"updTgl\">Bật/Tắt tự cập nhật</button></div><div class=\"sysrow\"><span class=\"lbl\">QR nổi · Pinggy</span><button id=\"ovOn\">Hiện QR</button><button id=\"ovOff\">Ẩn QR</button><button id=\"pgRe\">Đổi link</button></div><div class=\"sysrow\"><span class=\"lbl\">Bảng điều khiển</span><span class=\"url\" id=\"lanUrl\"></span></div><div class=\"sysrow\"><span class=\"lbl\">Link công khai</span><span class=\"url\" id=\"pgUrl\"></span></div><h2>Sửa lỗi video</h2><div class=\"sysrow\"><span class=\"lbl\">Link cũ / hết hạn</span><button id=\"fixRefresh\">Lấy link mới</button></div><div class=\"sysrow\"><span class=\"lbl\">Chỉ còn 360p</span><button id=\"fix360\">Phát bản 360p</button></div><div class=\"sysrow\"><span class=\"lbl\">Nguồn đang lỗi</span><button id=\"fixNext\">Đổi nguồn phát</button></div><div class=\"sysrow\"><span class=\"lbl\">Trình phát</span><button id=\"fixRestartP\">Khởi động lại</button></div><div class=\"sysrow\"><span class=\"lbl\">Ứng dụng</span><button id=\"fixRestartA\">Khởi động lại</button></div></div><div class=\"sheet\" id=\"plSheet\"><h3>Thêm vào playlist</h3><div id=\"plList\"></div><button class=\"plrow\" id=\"plClose\">Đóng</button></div><div id=\"toast\"></div><nav><div class=\"in\"><button id=\"tabBtnSearch\" class=\"on\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\"><circle cx=\"11\" cy=\"11\" r=\"6.5\"/><path d=\"M16 16l4.5 4.5\"/></svg><span class=\"lb\">Tìm bài</span></button><button id=\"tabBtnQueue\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg><span class=\"lb\">Hàng chờ</span><span class=\"bdg\" id=\"qBdg\" style=\"display:none\">0</span></button><button id=\"tabBtnPl\"><svg viewBox=\"0" +
        " 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"7\" cy=\"18\" r=\"2.6\"/><circle cx=\"17.5\" cy=\"16\" r=\"2.6\"/><path d=\"M9.6 18V7.5L20.1 6v10\"/></svg><span class=\"lb\">Playlist</span></button><button id=\"tabBtnSettings\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z\"/></svg><span class=\"lb\">Cài đặt</span></button></div></nav><script>/* v1.0.29: Pinggy free chen trang Caution truoc moi request tu trinh duyet (ke ca /api/*) - fetch() nhan ve HTML, r.json() nem loi va panel chet (loi v1.0.19). Shim nay boc window.fetch mot cho: neu body khong parse duoc JSON thi goi handshake (XHR GET + header X-Pinggy-No-Screen-Cookie + withCredentials) roi thu lai request mot lan. Moi fetch/post cua panel deu di qua day. */if(window.fetch){(function(){var _f=window.fetch.bind(window);function hs(){try{var x=new XMLHttpRequest();x.open('GET',location.href,true);x.withCredentials=true;x.setRequestHeader('X-Pinggy-No-Screen-Cookie','true');x.send()}catch(e){}}window.fetch=function(u,o){return _f(u,o).then(function(r){return r.clone().text().then(function(t){try{JSON.parse(t);return r}catch(e){hs();return new Promise(function(res){setTimeout(res,1500)}).then(function(){return _f(u,o)})}})});};hs()})();}document.addEventListener('copy',function(e){e.preventDefault()});document.addEventListener('contextmenu',function(e){e.preventDefault()});document.addEventListener('gesturestart',function(e){e.preventDefault()});document.addEventListener('dblclick',function(e){e.preventDefault()});var $=function(id){return document.getElementById(id)};function fmt(s){s=Math.max(0,s|0);var h=(s/3600)|0,m=((s%3600)/60)|0,x=s%60;return (h?h+':':'')+((h&&m<10)?'0':'')+m+':'+(x<10?'0':'')+x}function esc(t){var d=document.createElement('div');d.textContent=t||'';return d.innerHTML}function post(path,data){var b=new URLSearchParams(data||{});return fetch(path,{method:'POST',body:b}).then(function(r){return r.json()})}function itemRow(it,actions,opts){var d=document.createElement('div');d.className='row';d.innerHTML=(opts&&opts.drag?'<div class=\"drag\">'+IC_DRAG+'</div>':'')+'<img src=\"'+esc(it.thumb)+'\"><div class=\"inf\"><div class=\"t\">'+esc(it.title)+'</div><div class=\"a\">'+esc(it.artist)+(it.duration?' · '+fmt(it.duration):'')+'</div></div>';actions.forEach(function(a){var btn=document.createElement('button');if(a.icon)btn.innerHTML=a.icon;else btn.textContent=a.label;if(a.label)btn.title=a.label;if(a.cls)btn.className=a.cls;btn.onclick=function(){a.fn()};d.appendChild(btn)});if(opts&&(opts.swipeDel||opts.swipePl)){var w=document.createElement('div');w.className='swrap';var l1=document.createElement('div');l1.className='swlab swl';l1.innerHTML=IC_TRASH+'<span>Xoá</span>';var l2=document.createElement('div');l2.className='swlab swr';l2.innerHTML=IC_PL+'<span>Thêm playlist</span>';if(!opts.swipeDel)l1.style.display='none';if(!opts.swipePl)l2.style.display='none';w.appendChild(l1);w.appendChild(l2);w.appendChild(d);addSwipe(d,it,opts,l1,l2);return w}return d}function addSwipe(d,it,opts,L,R){var sx=0,sy=0,dx=0,mode=0,act=false;d.addEventListener('pointerdown',function(e){if(e.target.closest('button')||e.target.closest('.drag'))return;sx=e.clientX;sy=e.clientY;dx=0;mode=0;act=true;swiping=true;d.style.transition='';try{d.setPointerCapture(e.pointerId)}catch(_){}});d.addEventListener('pointermove',function(e){if(!act)return;var mx=e.clientX-sx,my=e.clientY-sy;if(!mode){if(Math.abs(mx)>12&&Math.abs(mx)>Math.abs(my))mode=1;else if(Math.abs(my)>12){act=false;swipi" +
        "ng=false;return}}if(mode===1){dx=mx;d.style.transform='translateX('+dx+'px)';if(L)L.style.opacity=dx>0?Math.min(1,dx/84):0;if(R)R.style.opacity=dx<0?Math.min(1,-dx/84):0}});function end(){swiping=false;if(!act)return;act=false;d.style.transition='transform .18s';if(mode===1&&dx>84&&opts.swipeDel){var idx=+(d.closest('.swrap')||d).dataset.idx;d.style.transform='translateX(105%)';setTimeout(function(){post('/api/control',{action:'remove',index:idx}).then(refresh)},170)}else if(mode===1&&dx<-84&&opts.swipePl){openPl(it);d.style.transform=''}else d.style.transform='';if(L)L.style.opacity=0;if(R)R.style.opacity=0;dx=0;mode=0}d.addEventListener('pointerup',end);d.addEventListener('pointercancel',end)}function refresh(){fetch('/api/state').then(function(r){return r.json()}).then(function(s){var c=s.current;if(c){$('npTitle').textContent=c.title||'';$('npArtist').textContent=c.artist||'';$('npImg').src=c.thumb||'';curDur=s.duration||0;if(!seekDrag){$('progPos').textContent=fmt(s.position);$('progDur').textContent=fmt(s.duration||c.duration||0);$('progFill').style.width=(s.duration>0?Math.min(100,s.position/s.duration*100):0)+'%'}}else{curDur=0;$('npTitle').textContent='Chưa phát bài nào';$('progPos').textContent='0:00';$('progDur').textContent='0:00';$('progFill').style.width='0%'}var hs=$('hdrStatus'),ht=$('hdrStatusTxt');if(s.playing){hs.className='status';ht.textContent='Đang phát'}else if(c){hs.className='status paused';ht.textContent='Đã dừng'}else{hs.className='status paused';ht.textContent='Chưa phát'}$('bPlay').innerHTML=s.playing?IC_PAUSE:IC_PLAY;if(!volDrag&&s.volume>=0){$('vol').value=s.volume;$('volVal').textContent=s.volume+'%'}var n=s.queue?s.queue.length:0;var bdg=$('qBdg');bdg.textContent=n;bdg.style.display=n>0?'':'none';$('lanUrl').textContent=s.lanUrl||'';$('pgUrl').textContent=s.pinggyUrl?(s.pinggyUrl+'/panel'):'';if(!qDrag&&!swiping){var q=$('queue');q.innerHTML='';if(!s.queue||!s.queue.length){q.innerHTML='<div class=\"empty\">Hàng chờ đang trống.</div>'}s.queue&&s.queue.forEach(function(it,i){var row=itemRow(it,[{icon:IC_PLAY,label:i===s.index?'Đang phát':'Phát',cls:i===s.index?'go':'',fn:function(){post('/api/control',{action:'jump',index:i}).then(refresh)}}],{drag:1,swipeDel:1,swipePl:1});row.dataset.idx=i;if(i===s.index)row.className+=' now';q.appendChild(row)})}}).catch(function(){})}var curQ='',curPage=0,searchDone=false,searchBusy=false,seenIds={};function showTab(w){$('tabSearch').style.display=w==='search'?'':'none';$('tabQueue').style.display=w==='queue'?'':'none';$('tabPl').style.display=w==='pl'?'':'none';$('tabSettings').style.display=w==='settings'?'':'none';$('tabBtnSearch').className=w==='search'?'on':'';$('tabBtnQueue').className=w==='queue'?'on':'';$('tabBtnPl').className=w==='pl'?'on':'';$('tabBtnSettings').className=w==='settings'?'on':'';if(w==='pl'){if(plOpenId)openPlist(plOpenId,plOpenName);else loadPlists()}if(w==='settings')updFoot();}$('tabBtnSearch').onclick=function(){showTab('search')};$('tabBtnQueue').onclick=function(){showTab('queue')};$('tabBtnPl').onclick=function(){showTab('pl')};$('tabBtnSettings').onclick=function(){showTab('settings')};function resultRow(it){return itemRow(it,[{icon:IC_PLAY,label:'Phát',cls:'go',fn:function(){post('/api/playnow',it).then(refresh)}},{icon:IC_ENQ,label:'+ Chờ',fn:function(){post('/api/enqueue',it).then(refresh)}}],{swipePl:1})}function loadPage(){if(searchBusy||searchDone||!curQ)return;searchBusy=true;var next=curPage+1;if(next>1)$('bMore').textContent='Đang tải thêm…';fetch('/api/search?q='+encodeURIComponent(curQ)+'&page='+next).then(function(r){return r.json()}).then(function(s){searchBusy=false;if(!s.ok){searchDone=true;$('bMore').style.display='none';if(curPage===0)$('results').innerHTML='<div class=\"empty\">'+(esc(s.error)||'Không tìm được.')+'</div>';return}curPage=next;var box=$('results');if(curPage===1)box.innerHTML='';if(!s.results.length){searchDone=true;if(curPage===1)box.innerHTML='<div class=\"empty\">Không thấy kết quả nào.</div>'}s.results.forEach(function(it){if(seenIds[it.id]" +
        ")return;seenIds[it.id]=1;box.appendChild(resultRow(it))});if(s.results.length<20)searchDone=true;$('bMore').style.display=searchDone?'none':'';$('bMore').textContent='Xem thêm kết quả'}).catch(function(){searchBusy=false;$('bMore').textContent='Xem thêm kết quả';if(curPage===0)$('results').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}function extractId(t){var m=t.match(/(?:youtube\\.com\\/(?:watch\\?[^\\s]*v=|shorts\\/|embed\\/|live\\/)|youtu\\.be\\/)([A-Za-z0-9_-]{11})/);if(m)return m[1];return /^[A-Za-z0-9_-]{11}$/.test(t)?t:null}function doSearch(){var q=$('q').value.trim();if(!q)return;if(extractId(q)){pasteLink(q,'now');return}curQ=q;curPage=0;searchDone=false;seenIds={};$('results').innerHTML='<div class=\"empty\">Đang tìm…</div>';$('bMore').style.display='none';loadPage()}function pasteLink(u,mode){var note=$('pasteNote');note.textContent='Đang lấy thông tin video…';post('/api/paste',{url:u,mode:mode}).then(function(s){if(s.ok&&s.item){note.textContent=(mode==='now'?'▶ Đang phát: ':'🎶 Đã thêm vào hàng chờ: ')+s.item.title;refresh()}else note.textContent=s.error||'Không xử lý được link này.'}).catch(function(){note.textContent='Mất kết nối tới thiết bị.'})}$('bPastePlay').onclick=function(){var u=$('q').value.trim();if(u){pasteLink(u,'now');$('q').value='';$('linkRow').style.display='none'}};$('bPasteQueue').onclick=function(){var u=$('q').value.trim();if(u){pasteLink(u,'queue');$('q').value='';$('linkRow').style.display='none'}};$('q').addEventListener('input',function(){$('linkRow').style.display=extractId($('q').value.trim())?'':'none'});$('bMore').onclick=loadPage;var scrollTick=false;window.addEventListener('scroll',function(){if(scrollTick)return;scrollTick=true;setTimeout(function(){scrollTick=false;var el=$('moreSentinel');if(!el)return;var r=el.getBoundingClientRect();if(r.top<window.innerHeight+600&&r.bottom>0)loadPage()},160)},{passive:true});if(window.IntersectionObserver){new IntersectionObserver(function(e){if(e[0].isIntersecting)loadPage()},{rootMargin:'400px'}).observe($('moreSentinel'))}$('q').addEventListener('keydown',function(e){if(e.key==='Enter')doSearch()});$('bPlay').onclick=function(){post('/api/control',{action:'toggle'}).then(refresh)};$('bNext').onclick=function(){post('/api/control',{action:'next'}).then(refresh)};$('bPrev').onclick=function(){post('/api/control',{action:'prev'}).then(refresh)};$('bClearQueue').onclick=function(){if(!confirm('Dọn sạch toàn bộ hàng chờ?'))return;post('/api/control',{action:'clear'}).then(refresh)};var volDrag=false,volTimer=null;$('vol').addEventListener('input',function(){volDrag=true;$('volVal').textContent=$('vol').value+'%';clearTimeout(volTimer);volTimer=setTimeout(function(){post('/api/control',{action:'volume',value:$('vol').value}).then(function(){volDrag=false;refresh()})},180)});var seekDrag=false,seekFrac=0,curDur=0;var progEl=document.querySelector('.prog');function seekFracOf(e){var r=progEl.getBoundingClientRect();var f=(e.clientX-r.left)/r.width;return f<0?0:f>1?1:f}progEl.addEventListener('pointerdown',function(e){if(curDur<=0)return;seekDrag=true;try{progEl.setPointerCapture(e.pointerId)}catch(_){}seekFrac=seekFracOf(e);$('progFill').style.width=(seekFrac*100)+'%';$('progPos').textContent=fmt(seekFrac*curDur)});progEl.addEventListener('pointermove',function(e){if(!seekDrag)return;seekFrac=seekFracOf(e);$('progFill').style.width=(seekFrac*100)+'%';$('progPos').textContent=fmt(seekFrac*curDur)});progEl.addEventListener('pointerup',function(e){if(!seekDrag)return;seekDrag=false;post('/api/control',{action:'seek',pos:Math.round(seekFrac*curDur*1000)}).then(refresh)});progEl.addEventListener('pointercancel',function(){if(seekDrag){seekDrag=false;refresh()}});var SR=window.SpeechRecognition||window.webkitSpeechRecognition;var micBusy=false;function micFallback(){$('q').focus();alert('Trình duyệt này không cho trang web dùng mic trực tiếp. Cách nói để tìm: chạm vào ô tìm kiếm rồi bấm nút mic trên bàn phím điện thoại (iPhone và Android đều có).')};$('bMic').onclick=function(){if(!SR){micFallback();return}if(micBusy)return;micBusy=true;try{var r=new SR();r.lang='vi-VN';r.continuous=false;r.interimResults=false;r.maxAlternatives=1;r.onresult=function(e){$('q').value=e.results[0][0].transcript;doSearch()};r.onend=function(){micBusy=false};r.onerror=function(ev){micBusy=false;if(ev.error==='not-allowed'||ev.error==='service-not-allowed'||ev.error==='audio-capture')micFallback()};r.start()}catch(_){micBusy=false;micFallback()}};var IC_PLAY='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M8 5.5v13l11-6.5z\"/></svg>',IC_PAUSE='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><rect x=\"6.5\" y=\"5\" width=\"3.6\" height=\"14\" rx=\"1.2\"/><rect x=\"13.9\" y=\"5\" width=\"3.6\" height=\"14\" rx=\"1.2\"/></svg>',IC_QTAB='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg>',IC_ENQ='<svg viewBox=\"0 0 " +
        "24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h9.5M4 12h9.5M4 17.5h5\"/><path d=\"M15.8 14.6l5.7 3.4-5.7 3.4z\" fill=\"currentColor\" stroke=\"none\"/></svg>',IC_PL='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 6.5h10M4 12h10M4 17.5h6\"/><path d=\"M17.8 14.8v6.4M14.6 18h6.4\"/></svg>',IC_TRASH='<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M4 7h16M9 7V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v2M6.5 7l1 12A1.5 1.5 0 0 0 9 20.5h6a1.5 1.5 0 0 0 1.5-1.5l1-12M10 11v6M14 11v6\"/></svg>',IC_DRAG='<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><circle cx=\"9\" cy=\"6.5\" r=\"1.7\"/><circle cx=\"15\" cy=\"6.5\" r=\"1.7\"/><circle cx=\"9\" cy=\"12\" r=\"1.7\"/><circle cx=\"15\" cy=\"12\" r=\"1.7\"/><circle cx=\"9\" cy=\"17.5\" r=\"1.7\"/><circle cx=\"15\" cy=\"17.5\" r=\"1.7\"/></svg>';var plItem=null,toastTimer=0;function toast(m){var t=$('toast');t.textContent=m;t.style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(function(){t.style.display='none'},2600)}function openPl(it){plItem=it;$('plList').innerHTML='<div class=\"note\">Đang tải…</div>';$('plSheet').classList.add('open');fetch('/api/playlists').then(function(r){return r.json()}).then(function(s){var box=$('plList');box.innerHTML='';(s.playlists||[]).forEach(function(pl){var b=document.createElement('button');b.className='plrow';b.textContent=pl.name+' · '+pl.count+' bài';b.onclick=function(){addToPl(pl.id,'')};box.appendChild(b)});var nb=document.createElement('button');nb.className='plrow';nb.textContent='＋ Tạo playlist mới';nb.onclick=function(){var n=prompt('Tên playlist mới:');if(n)addToPl('new',n)};box.appendChild(nb)}).catch(function(){$('plList').innerHTML='<div class=\"note\">Không tải được danh sách playlist.</div>'})}function addToPl(pid,name){if(!plItem)return;post('/api/playlist-add',{playlist:pid,name:name,id:plItem.id,title:plItem.title,artist:plItem.artist,thumb:plItem.thumb,duration:plItem.duration}).then(function(s){$('plSheet').classList.remove('open');toast(s&&s.ok?(s.added>0?'Đã thêm vào “'+s.name+'”':'Bài đã có trong “'+s.name+'”'):'Không thêm được')})}$('plClose').onclick=function(){$('plSheet').classList.remove('open')};var qDrag=null,swiping=false;$('queue').addEventListener('pointerdown',function(e){var h=e.target.closest?e.target.closest('.drag'):null;if(!h)return;var row=h.closest('.swrap')||h.parentElement;e.preventDefault();try{h.setPointerCapture(e.pointerId)}catch(_){}qDrag={row:row,from:+row.dataset.idx,y0:e.clientY};row.classList.add('dragging')});document.addEventListener('pointermove',function(e){if(!qDrag)return;qDrag.row.style.transform='translateY('+(e.clientY-qDrag.y0)+'px)'});document.addEventListener('pointerup',function(e){if(!qDrag)return;var row=qDrag.row,from=qDrag.from;row.style.transform='';row.classList.remove('dragging');var rows=[].slice.call($('queue').children).filter(function(r){return r!==row});var to=0;rows.forEach(function(r){var b=r.getBoundingClientRect();if(e.clientY>b.top+b.height/2)to++});qDrag=null;if(to!==from)post('/api/control',{action:'move',from:from,to:to}).then(refresh);else refresh()});document.addEventListener('pointercancel',function(){if(qDrag){qDrag.row.style.transform='';qDrag.row.classList.remove('dragging');qDrag=null;refresh()}});var plOpenId=null,plOpenName='';function loadPlists(){fetch('/api/playlists').then(function(r){return r.json()}).then(function(s){var box=$('plists');box.innerHTML='';var arr=s.playlists||[];if(!arr.length)box.innerHTML='<div class=\"empty\">Chưa có playlist nào. Bấm nút bên dưới để tạo mới, hoặc vuốt trái một bài rồi thêm vào playlist.</div>';arr.forEach(function(pl){var b=document.createElement('button');b.className='plrow';b.textContent=pl.name+' · '+pl.count+' bài';b.onclick=function(){openPlist(pl.id,pl.name)};box.appendChild(b)})}).catch(function(){$('plists'" +
        ").innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}function openPlist(id,name){plOpenId=id;plOpenName=name||'';$('plHome').style.display='none';$('plDetail').style.display='';$('plName').textContent=plOpenName;$('plTracks').innerHTML='<div class=\"empty\">Đang tải…</div>';fetch('/api/playlist?id='+encodeURIComponent(id)).then(function(r){return r.json()}).then(function(s){if(!s.ok){$('plTracks').innerHTML='<div class=\"empty\">Không mở được playlist này.</div>';return}plOpenName=s.name;$('plName').textContent=s.name;var box=$('plTracks');box.innerHTML='';if(!s.items||!s.items.length)box.innerHTML='<div class=\"empty\">Playlist đang trống.</div>';(s.items||[]).forEach(function(it){box.appendChild(itemRow(it,[{icon:IC_PLAY,label:'Phát',cls:'go',fn:function(){post('/api/playnow',it).then(refresh)}},{icon:IC_TRASH,label:'Xoá khỏi playlist',fn:function(){post('/api/playlist-remove',{playlist:plOpenId,id:it.id}).then(function(r){if(r&&r.ok){toast('Đã xoá khỏi playlist');openPlist(plOpenId,plOpenName)}else toast('Không xoá được')})}}]))})}).catch(function(){$('plTracks').innerHTML='<div class=\"empty\">Mất kết nối tới thiết bị.</div>'})}$('plBack').onclick=function(){plOpenId=null;$('plDetail').style.display='none';$('plHome').style.display='';loadPlists()};$('bPlNew').onclick=function(){var n=prompt('Tên playlist mới:');if(!n)return;post('/api/playlist-create',{name:n}).then(function(s){if(s&&s.ok){toast('Đã tạo playlist mới');loadPlists()}else toast('Không tạo được')})};$('bPlPlayAll').onclick=function(){if(!plOpenId)return;post('/api/playlist-play',{playlist:plOpenId,index:0}).then(function(s){if(s&&s.ok){toast('Đang phát playlist');refresh()}else toast('Playlist đang trống')})};$('bPlEnqAll').onclick=function(){if(!plOpenId)return;post('/api/playlist-enqueue',{playlist:plOpenId}).then(function(s){if(s&&s.ok){toast('Đã thêm '+s.count+' bài vào hàng chờ');refresh()}else toast('Không thêm được')})};$('bPlDel').onclick=function(){if(!plOpenId)return;if(!confirm('Xoá playlist này?'))return;post('/api/playlist-delete',{playlist:plOpenId}).then(function(s){if(s&&s.ok){plOpenId=null;$('plDetail').style.display='none';$('plHome').style.display='';loadPlists();toast('Đã xoá playlist')}else toast('Không xoá được')})};function updFoot(){fetch('/api/state').then(function(r){return r.json()}).then(function(s){var us=s.updateState||'none';var ut=us==='downloading'?'đang tải':us==='ready'?'đã tải xong — xem thông báo trên TV để cài đặt':us==='checking'?'đang kiểm tra':us==='error'?'lỗi, sẽ thử lại sau':us==='grant'?'cần cấp quyền cài đặt trong Cài đặt':'';$('upd').textContent=(s.autoUpdate?'Bật':'Tắt')+(s.updateAvailable?' • có bản '+s.updateVersion+(ut?' — '+ut:''):ut?' • '+ut:'')}).catch(function(){})}/* v1.0.37: Sua loi video (thu cong) — cac nut o tab Cai dat goi /api/fix, tra ve {ok,message} de toast. */function fixBtn(id,action,cfm){$(id).onclick=function(){if(cfm&&!confirm(cfm))return;post('/api/fix',{action:action}).then(function(s){toast(s&&s.message?s.message:(s&&s.ok?'Xong':'Không làm được'))})}}fixBtn('fixRefresh','refresh');fixBtn('fix360','force360');fixBtn('fixNext','nextsource');fixBtn('fixRestartP','restartplayer');fixBtn('fixRestartA','restartapp','Khởi động lại ứng dụng? Hàng chờ vẫn giữ nguyên.');$('updTgl').onclick=function(){post('/api/update',{action:'toggle'}).then(updFoot)};$('ovOn').onclick=function(){post('/api/overlay',{action:'enable'}).then(updFoot)};$('ovOff').onclick=function(){post('/api/overlay',{action:'disable'}).then(updFoot)};$('pgRe').onclick=function(){post('/api/pinggy',{action:'restart'}).then(function(){setTimeout(updFoot,2500)})};refresh();updFoot();setInterval(refresh,2500);setInterval(updFoot,15000);</script></div></body></html>\n";
    /** TV stage (served at /stage): bare transparent page, exactly like the
     * original Gathin stage — zero UI on the TV, video fullscreen from the
     * SurfaceView behind the transparent WebView; all controls live on the
     * phone panel (/panel). No script: nothing on the stage needs JS. */
    private static final String STAGE_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>Karaoke YouTube</title><style>html,body{margin:0;padding:0;background:transparent;height:100%;overflow:hidden}</style></head><body></body></html>";
}
