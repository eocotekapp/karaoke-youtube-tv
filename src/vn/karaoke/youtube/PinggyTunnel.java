package vn.karaoke.youtube;

import android.content.Context;
import android.content.SharedPreferences;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pinggy tunnel — PURE-JAVA SSH (JSch 0.2.x, mwiede fork), in-process,
 * NO cloudflared binary, NO Termux, NO extra process. Replaces TunnelManager.
 *
 * NOTE (2026-11-27): JSch 0.1.55 (2018) was REMOVED — it only offers ssh-rsa
 * (SHA-1) host-key algorithms, and Pinggy's modern SSH server refuses them:
 * every connect failed with "Algorithm negotiation fail". The mwiede fork
 * 0.2.9 offers rsa-sha2-256/512, ssh-ed25519, ecdsa and curve25519 KEX,
 * same package (com.jcraft.jsch) and same API, drop-in replacement.
 *
 * Free Pinggy: tunnel dies after ~60 min and the URL is random each time, so this
 * class auto-reconnects (scheduled re-dial at ~55 min, plus reconnect on drop)
 * and always reports the CURRENT public URL to listeners.
 *
 * How it works: SSH to a.pinggy.io / free.pinggy.io port 443, request remote
 * forwarding -R0:localhost:8792, read the printed https://<name>.pinggy.link
 * URL from the shell channel output. No account/token needed for the free tier.
 * Optional basic-auth: Pinggy encodes it in the SSH username as b:user:pass.
 */
public class PinggyTunnel {
    public interface Listener {
        void onUrl(String url);
        void onState(String state);
    }

    private static final Pattern URL_RE = Pattern.compile("https://[a-z0-9-]+\\.pinggy\\.link");
    private static final long RECONNECT_BEFORE_MS = 55L * 60 * 1000; // free tier dies ~60 min
    private static final long RETRY_BASE_MS = 10 * 1000;

    private final Context ctx;
    private final Listener listener;
    private volatile Thread worker;
    private volatile boolean wantRun = false;
    private volatile String publicUrl = "";
    private volatile String state = "off";

    public PinggyTunnel(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isEnabled() {
        return ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }
    public void setEnabled(boolean on) {
        ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", on).apply();
        if (on) start(); else stop();
    }
    public String getAuthUser() {
        return ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE)
                .getString("authUser", "");
    }
    public String getAuthPass() {
        return ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE)
                .getString("authPass", "");
    }
    public void setAuth(String user, String pass) {
        ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE)
                .edit().putString("authUser", user == null ? "" : user)
                .putString("authPass", pass == null ? "" : pass).apply();
    }
    public String getPublicUrl() { return publicUrl; }
    public String getState() { return state; }

    private void setState(String s) {
        state = s;
        // Full telemetry: every tunnel state is logged, and the important
        // ones (online / any error) are uploaded immediately so Mesu sees
        // the tunnel health without anyone screenshotting the stage.
        StartupLog.log("Pinggy: " + s + (publicUrl.isEmpty() ? "" : " " + publicUrl));
        if ("online".equals(s) || s.startsWith("error")) LogUploader.send("PINGGY");
        if (listener != null) listener.onState(s);
    }

    public synchronized void start() {
        if (!isEnabled()) { setState("disabled"); return; }
        if (worker != null && worker.isAlive()) return;
        wantRun = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "pinggy-tunnel");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        wantRun = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
        publicUrl = "";
        setState("off");
    }

    private void runLoop() {
        long retryDelay = RETRY_BASE_MS;
        while (wantRun) {
            long startedAt = System.currentTimeMillis();
            try {
                setState("connecting");
                runOneSession();
                // runOneSession returns on disconnect or on the 55-min re-dial
                retryDelay = RETRY_BASE_MS;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                StartupLog.log("Pinggy LOI: " + e);
                setState("error: " + (e.getMessage() == null ? "ssh failed" : e.getMessage()));
            }
            if (!wantRun) break;
            // If the session lived almost the full 55 min, re-dial immediately;
            // otherwise back off a little before retrying.
            long lived = System.currentTimeMillis() - startedAt;
            long wait = lived > RECONNECT_BEFORE_MS - 60 * 1000 ? 2000 : retryDelay;
            retryDelay = Math.min(retryDelay * 2, 120 * 1000);
            try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        setState("off");
    }

    private void runOneSession() throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("karaoke_pinggy", Context.MODE_PRIVATE);
        String host = p.getString("host", "free.pinggy.io");
        String user = "free";
        String au = getAuthUser(), ap = getAuthPass();
        if (!au.isEmpty()) user = "b:" + au + ":" + ap; // Pinggy basic-auth flag in username
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, 443);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("ServerAliveInterval", "30");
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setPassword(""); // free tier: no password; server accepts the session for forwarding
        session.connect(20000);
        try {
            session.setPortForwardingR(0, "127.0.0.1", RemoteServer.PORT);
            // Open a shell channel: Pinggy prints the public URL on it.
            ChannelShell ch = (ChannelShell) session.openChannel("shell");
            ch.setPty(false);
            BufferedReader in = new BufferedReader(new InputStreamReader(ch.getInputStream(), "UTF-8"));
            ch.connect(10000);
            setState("waiting-url");
            long deadline = System.currentTimeMillis() + RECONNECT_BEFORE_MS;
            String line;
            boolean gotUrl = false;
            while (wantRun && System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                if (!session.isConnected()) break;
                if (in.ready()) {
                    line = in.readLine();
                    if (line == null) break;
                    Matcher m = URL_RE.matcher(line);
                    if (m.find()) {
                        publicUrl = m.group();
                        gotUrl = true;
                        setState("online");
                        if (listener != null) listener.onUrl(publicUrl);
                    }
                } else {
                    // keep the session alive; check deadline every 500 ms
                    try { Thread.sleep(500); } catch (InterruptedException ie) { throw ie; }
                    if (!gotUrl && System.currentTimeMillis() > deadline - RECONNECT_BEFORE_MS + 30000) {
                        // no URL after 30 s: give up this session, retry
                        break;
                    }
                }
            }
            try { ch.disconnect(); } catch (Exception ignored) { }
        } finally {
            try { session.disconnect(); } catch (Exception ignored) { }
        }
        publicUrl = "";
        if (wantRun) setState("reconnecting");
    }
}
