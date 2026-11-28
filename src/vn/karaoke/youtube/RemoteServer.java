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
        try { o.put("lanUrl", lanUrl()); } catch (Exception e) { safePut(o, "lanUrl", ""); }
        try { o.put("pinggyUrl", tunnel == null ? "" : tunnel.getPublicUrl()); } catch (Exception e) { safePut(o, "pinggyUrl", ""); }
        try { o.put("pinggyState", tunnel == null ? "off" : tunnel.getState()); } catch (Exception e) { safePut(o, "pinggyState", "off"); }
        try { o.put("pinggyEnabled", tunnel != null && tunnel.isEnabled()); } catch (Exception e) { safePut(o, "pinggyEnabled", false); }
        try { o.put("sleepMinutesLeft", service == null ? 0 : service.sleepMinutesLeft()); } catch (Exception e) { safePut(o, "sleepMinutesLeft", 0); }
        return o;
    }

    /** Best-effort put — never throws, so stateJson() always returns a full object. */
    private static void safePut(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (Exception ignored) { }
    }

    private LibraryState.Item itemFromParams(Map<String, String> q) {
        String id = q.get("id");
        if (id == null || id.isEmpty()) id = YoutubeApi.extractId(q.get("url"));
        if (id == null || id.isEmpty()) id = YoutubeApi.extractId(q.get("q"));
        if (id == null) return null;
        long dur = 0;
        try { dur = Long.parseLong(q.get("durationMs") == null ? "0" : q.get("durationMs")); } catch (Exception ignored) { }
        return new LibraryState.Item(id, q.get("title"), q.get("channel"), dur);
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
                if (query == null || query.isEmpty()) { sendJson(out, new JSONObject().put("error", "empty query")); return; }
                library.recordSearch(query);
                List<YoutubeApi.Video> res = api.search(query);
                JSONArray arr = new JSONArray();
                for (YoutubeApi.Video v : res) arr.put(v.toJson());
                sendJson(out, new JSONObject().put("results", arr));
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
                    try { controlVolume(Float.parseFloat(q.get("v")) / 100f); } catch (Exception ignored) { }
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
                if (id == null) { sendJson(out, new JSONObject().put("error", "not a youtube link")); return; }
                JSONObject r = new JSONObject(new MesuResolver().resolve(id));
                LibraryState.Item it = new LibraryState.Item(id,
                        r.optString("title", ""), "", r.optLong("durationMs", 0));
                if ("playnow".equals(q.get("mode"))) { library.enqueueAtFront(it); playItem(it); }
                else library.enqueue(it);
                sendJson(out, stateJson());
                return;
            }
            case "/api/channel": {
                // v1: channel search is cut; return empty with a clear note.
                sendJson(out, new JSONObject().put("results", new JSONArray()).put("note", "channel search not in v1"));
                return;
            }
            case "/api/playlists": {
                JSONArray arr = new JSONArray();
                for (LibraryState.Playlist pl : library.playlists) {
                    JSONObject p = new JSONObject();
                    p.put("name", pl.name);
                    JSONArray items = new JSONArray();
                    for (LibraryState.Item it : pl.items) items.put(it.toJson());
                    p.put("items", items);
                    arr.put(p);
                }
                sendJson(out, new JSONObject().put("playlists", arr));
                return;
            }
            case "/api/playlist": {
                String name = q.get("name");
                JSONArray items = new JSONArray();
                if (name != null) for (LibraryState.Playlist pl : library.playlists)
                    if (pl.name.equals(name)) for (LibraryState.Item it : pl.items) items.put(it.toJson());
                sendJson(out, new JSONObject().put("name", name == null ? "" : name).put("items", items));
                return;
            }
            case "/api/playlist-create": {
                String name = q.get("name");
                if (name != null && !name.isEmpty()) library.getOrCreatePlaylist(name);
                sendJson(out, stateJson());
                return;
            }
            case "/api/playlist-delete": {
                if (q.get("name") != null) library.deletePlaylist(q.get("name"));
                sendJson(out, stateJson());
                return;
            }
            case "/api/playlist-add": {
                LibraryState.Item it = itemFromParams(q);
                String name = q.get("name");
                if (it != null && name != null) library.getOrCreatePlaylist(name).items.add(it);
                library.persist();
                sendJson(out, stateJson());
                return;
            }
            case "/api/playlist-remove": {
                String name = q.get("name");
                if (name != null) for (LibraryState.Playlist pl : library.playlists)
                    if (pl.name.equals(name)) {
                        try { pl.items.remove(Integer.parseInt(q.get("index"))); } catch (Exception ignored) { }
                    }
                library.persist();
                sendJson(out, stateJson());
                return;
            }
            case "/api/playlist-enqueue": {
                String name = q.get("name");
                if (name != null) for (LibraryState.Playlist pl : library.playlists)
                    if (pl.name.equals(name)) for (LibraryState.Item it : pl.items) library.enqueue(it);
                sendJson(out, stateJson());
                return;
            }
            case "/api/playlist-play": {
                String name = q.get("name");
                if (name != null) for (LibraryState.Playlist pl : library.playlists)
                    if (pl.name.equals(name) && !pl.items.isEmpty()) {
                        library.clearQueue();
                        for (LibraryState.Item it : pl.items) library.enqueue(it);
                        library.jumpTo(0);
                        playItem(library.current());
                    }
                sendJson(out, stateJson());
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

    /** Phone control panel (served at / and /panel). */
    private static final String PAGE =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>Karaoke YouTube — Điều khiển</title><style>" + COMMON_CSS + "</style></head><body>" +
        "<h1>🎵 Karaoke YouTube</h1>" +
        "<div class='muted' id='now'>Đang tải…</div>" +
        "<div class='row'><button onclick=\"ctl('toggle')\">⏯ Phát / Tạm dừng</button>" +
        "<button onclick=\"ctl('prev')\">⏮ Trước</button><button onclick=\"ctl('next')\">⏭ Sau</button>" +
        "<button onclick=\"ctl('stop')\">⏹ Dừng</button></div>" +
        "<div class='row'><span>Âm lượng</span><input type='range' min='0' max='100' value='100' style='flex:1' onchange=\"ctl('volume&v='+this.value)\">" +
        "<span>Hẹn giờ tắt</span><button onclick=\"ctl('sleep&minutes=15')\">15p</button><button onclick=\"ctl('sleep&minutes=30')\">30p</button><button onclick=\"ctl('sleep&minutes=60')\">60p</button><button onclick=\"ctl('sleep&minutes=0')\">Hủy</button></div>" +
        "<h2>Tìm bài hát</h2><div class='row'><input id='q' placeholder='Tên bài hát, ca sĩ…' onkeydown='if(event.key===\"Enter\")search()'>" +
        "<button onclick='search()'>🔍 Tìm</button></div>" +
        "<div class='row'><input id='paste' placeholder='Dán link YouTube…' style='flex:1'><button onclick='paste()'>Phát ngay</button><button onclick='pasteQ()'>+ Hàng đợi</button></div>" +
        "<div id='results'></div><h2>Hàng đợi</h2><div id='queue'></div>" +
        "<h2>Playlist</h2><div id='playlists'></div>" +
        "<h2>Link</h2><div class='url' id='links'></div>" +
        "<div class='row'><span>Pinggy:</span><button onclick=\"pinggy('enable')\">Bật</button><button onclick=\"pinggy('disable')\">Tắt</button><button onclick=\"pinggy('restart')\">Đổi link</button><span class='muted' id='pg'></span></div>" +
        "<div class='row'><span>QR nổi:</span><button onclick=\"overlay('enable')\">Hiện</button><button onclick=\"overlay('disable')\">Ẩn</button><button onclick=\"overlay('grant')\">Cấp quyền</button><span class='muted'>QR chỉ hiện link Pinggy, tunnel chết thì tự ẩn</span></div>" +
        "<script>" +
        // v1.0.19: Pinggy free serves a "Caution" interstitial to browser UAs on
        // EVERY request (incl. /api/state) — fetch() gets HTML, r.json() throws and
        // refresh() dies, leaving "Đang tải…" forever (seen 2026-11-28 in a real
        // browser even after clicking "Enter site"). Pinggy's own bypass: an XHR
        // GET to the page URL with header X-Pinggy-No-Screen-Cookie: true +
        // withCredentials sets the bypass cookie/whitelist (response body is
        // empty by design). The panel fires that handshake itself on load and
        // apiSafe() re-fires it + retries once on any JSON-parse failure, so a
        // button can never die silently on an interstitial response. A retry
        // is safe: a parse failure means Pinggy intercepted the request, so
        // it never reached our server (no double-execution of control actions).
        "function pinggyHandshake(){try{var x=new XMLHttpRequest();x.open('GET',location.href,true);x.withCredentials=true;x.setRequestHeader('X-Pinggy-No-Screen-Cookie','true');x.send();}catch(e){}}" +
        "async function apiSafe(p){try{const r=await fetch(p);const t=await r.text();return JSON.parse(t);}catch(e){pinggyHandshake();await new Promise(function(res){setTimeout(res,1500);});const r2=await fetch(p);return r2.json();}}" +
        "async function api(p){return apiSafe(p);}" +
        "async function ctl(a){await api('/api/control?action='+a);refresh();}" +
        "async function pinggy(a){await api('/api/pinggy?action='+a);refresh();}" +
        "async function overlay(a){await api('/api/overlay?action='+a);refresh();}" +
        "async function search(){const q=document.getElementById('q').value;if(!q)return;" +
        "const d=await api('/api/search?q='+encodeURIComponent(q));" +
        "document.getElementById('results').innerHTML=(d.results||[]).map(v=>\"<div class='item' onclick=\\\"playnow('\"+v.id+\"','\"+v.title.replace(/'/g,'')+\"')\\\">\"+(v.thumb?\"<img src='\"+v.thumb+\"'>\":\"\")+v.title+\"<br><span class='muted'>\"+v.channel+\" • \"+v.durationText+\"</span> <button onclick=\\\"event.stopPropagation();enqueue('\"+v.id+\"','\"+v.title.replace(/'/g,'')+\"')\\\">+ Đợi</button></div>\").join('')||'Không tìm thấy';}" +
        "async function enqueue(id,t){await api('/api/enqueue?id='+id+'&title='+encodeURIComponent(t));refresh();}" +
        "async function playnow(id,t){await api('/api/playnow?id='+id+'&title='+encodeURIComponent(t));refresh();}" +
        "async function paste(){const u=document.getElementById('paste').value;await api('/api/paste?mode=playnow&url='+encodeURIComponent(u));refresh();}" +
        "async function pasteQ(){const u=document.getElementById('paste').value;await api('/api/paste?url='+encodeURIComponent(u));refresh();}" +
        "async function refresh(){try{const s=await api('/api/state');" +
        "const cur=s.current&&s.current.id?('▶ '+s.current.title):'Chưa phát bài nào';" +
        "document.getElementById('now').textContent=cur+(s.sleepMinutesLeft?' • Tắt sau '+s.sleepMinutesLeft+'p':'');" +
        "document.getElementById('queue').innerHTML=(s.queue||[]).map((v,i)=>\"<div class='item'>\"+(i===s.currentIndex?'▶ ':'')+v.title+\" <button onclick=\\\"jump(\"+i+\")\\\">Phát</button> <button onclick=\\\"rm(\"+i+\")\\\">Xóa</button></div>\").join('')||'Hàng đợi trống';" +
        "document.getElementById('links').innerHTML='LAN: '+s.lanUrl+'/panel'+(s.pinggyUrl?'<br>Internet (Pinggy): '+s.pinggyUrl+'/panel ('+s.pinggyState+')':'<br>Internet (Pinggy): '+s.pinggyState);" +
        "document.getElementById('pg').textContent=s.pinggyState;" +
        "const pl=await api('/api/playlists');" +
        "document.getElementById('playlists').innerHTML=(pl.playlists||[]).map(p=>\"<div class='item'>\"+p.name+\" (\"+p.items.length+\") <button onclick=\\\"plPlay('\"+p.name+\"')\\\">Phát</button></div>\").join('')||'Chưa có playlist';}catch(e){document.getElementById('now').textContent='Đang kết nối lại…';}}" +
        "async function jump(i){await api('/api/control?action=jump&index='+i);refresh();}" +
        "async function rm(i){await api('/api/control?action=remove&index='+i);refresh();}" +
        "async function plPlay(n){await api('/api/playlist-play?name='+encodeURIComponent(n));refresh();}" +
        "pinggyHandshake();setTimeout(function(){refresh();setInterval(refresh,5000);},800);" +
        "</script></body></html>";

    /** TV stage (served at /stage): bare transparent page, exactly like the
     * original Gathin stage — zero UI on the TV, video fullscreen from the
     * SurfaceView behind the transparent WebView; all controls live on the
     * phone panel (/panel). No script: nothing on the stage needs JS. */
    private static final String STAGE_HTML =
        "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>Karaoke YouTube</title><style>html,body{margin:0;padding:0;background:transparent;height:100%;overflow:hidden}</style></head><body></body></html>";
}
