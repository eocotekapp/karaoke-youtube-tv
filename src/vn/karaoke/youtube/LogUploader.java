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
        return "Karaoke " + tag + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + " SDK " + Build.VERSION.SDK_INT
                + " ABI " + Build.CPU_ABI + "\n"
                + "Time: " + new Date().toString() + "\n\n"
                + StartupLog.getText();
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

    /** Blocking send with a ~4 s cap (crash path: the process is dying anyway). */
    public static void sendSync(final Context ctx, final String tag) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { post(buildBody(ctx, tag)); } catch (Exception ignored) { }
            }
        }, "log-upload-sync");
        t.setDaemon(true);
        t.start();
        try { t.join(4000); } catch (InterruptedException ignored) { }
    }
}
