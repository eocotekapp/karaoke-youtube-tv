package vn.karaoke.youtube;

import android.content.Context;
import android.os.Build;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;

/**
 * Sends the startup log to Mesu automatically (webhook.site) so the user
 * never has to photograph the screen: open the app, the log arrives.
 * Best-effort, never blocks startup; the crash path waits at most ~4 s.
 * Endpoint is a webhook.site token created 2026-11-27, expires ~7 days
 * after creation — if it stops receiving, mint a fresh token and rebuild.
 */
public class LogUploader {
    public static final String ENDPOINT =
            "https://webhook.site/0f050b91-2325-4fa8-8fa6-4e6be74c9286";
    /** Read the logs in a browser (same token): */
    public static final String VIEW_URL =
            "https://webhook.site/#!/view/0f050b91-2325-4fa8-8fa6-4e6be74c9286";

    private static String buildBody(Context ctx, String tag) {
        return buildBody(ctx, tag, null, null);
    }

    private static String buildBody(Context ctx, String tag, String threadName, String trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("Karaoke ").append(tag).append("\n")
                .append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE)
                .append(" SDK ").append(Build.VERSION.SDK_INT)
                .append(" ABI ").append(Build.CPU_ABI).append("\n")
                .append("Time: ").append(new Date().toString()).append("\n");
        if (threadName != null) sb.append("Crash thread: ").append(threadName).append("\n");
        if (trace != null) sb.append("\n--- STACK TRACE ---\n").append(trace).append("\n");
        sb.append("\n--- STARTUP LOG ---\n").append(StartupLog.getText());
        return sb.toString();
    }

    private static int post(String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    /** Fire-and-forget send on a background thread. */
    public static void send(final Context ctx, final String tag) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int code = post(buildBody(ctx, tag));
                    StartupLog.log(ctx, "LogUploader " + tag + " -> HTTP " + code);
                } catch (Exception e) {
                    StartupLog.log(ctx, "LogUploader " + tag + " LOI: " + e);
                }
            }
        }, "log-upload").start();
    }

    /** Fire-and-forget send without a Context (any feature class can call it). */
    public static void send(final String tag) { send(StartupLog.appContext(), tag); }

    /** Blocking send with a ~4 s cap (crash path: the process is dying anyway). */
    public static void sendSync(final Context ctx, final String tag) {
        sendSyncWithTrace(ctx, tag, null, null);
    }

    /** Crash send including the thread name + full stack trace. */
    public static void sendSyncWithTrace(final Context ctx, final String tag,
                                         final String threadName, final String trace) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { post(buildBody(ctx, tag, threadName, trace)); } catch (Exception ignored) { }
            }
        }, "log-upload-sync");
        t.setDaemon(true);
        t.start();
        try { t.join(4000); } catch (InterruptedException ignored) { }
    }
}
