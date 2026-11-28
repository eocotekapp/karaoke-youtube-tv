package vn.karaoke.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v1.0.27: SELF-UPDATE (semi-auto). The user is tired of installing every
 * new version by hand through Downloader codes, so the app now updates
 * itself — as far as a stock, unrooted box allows.
 *
 * HONEST LIMIT (do not claim otherwise anywhere — code, README or panel):
 * a fully SILENT install is impossible for a normal app on a stock box
 * (only system apps / root can install without the user's tap). What the
 * app does: detect a new version, download it in the background, verify
 * it, and show the SYSTEM install screen — the user presses "Cài đặt"
 * once on the remote. That is the whole design.
 *
 * SOURCE OF TRUTH: a stable version file in the public GitHub repo
 * (the parent updates it on every release; the app never hardcodes a
 * version-specific Vercel URL):
 *   https://raw.githubusercontent.com/eocotekapp/karaoke-youtube-tv/main/version.json
 *   {"versionCode": 28, "versionName": "1.0.27-pinggy",
 *    "apkUrl": "https://karaoke-youtube-apk-v24.vercel.app/KaraokeYouTube.apk"}
 * Fetched over plain HTTPS (the box has internet; no Pinggy tunnel
 * involved). The APK is always signed with the same keystore, so
 * installing over the old version works.
 *
 * CADENCE: one check ~20 s after the service starts (PlayerService) and
 * every ~6 h while the service runs, gated by the existing 10 s watchdog
 * tick (no new timer service). A failed check retries in 30 min.
 * A finished download is never re-downloaded (SharedPreferences
 * `update_downloaded_version`); a verified download re-prompts the
 * install on the next check / app start, never nags in between.
 *
 * LOGS: every step logs "Cap nhat: ..." to StartupLog. The UPDATE upload
 * tag fires ONLY when a new version is found and when a download
 * completes, once per version each — the webhook token caps at 50
 * requests, so routine no-update checks upload NOTHING.
 */
public class UpdateChecker {
    public static final String VERSION_URL =
            "https://raw.githubusercontent.com/eocotekapp/karaoke-youtube-tv/main/version.json";
    private static final String PREFS = "karaoke_prefs";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000; // 6 h
    private static final long RETRY_AFTER_ERROR_MS = 30L * 60 * 1000; // 30 min
    private static final int NOTIF_UPDATE_ID = 8794;
    private static final String CHANNEL_UPDATE = "karaoke_update";
    private static final String PROVIDER_AUTHORITY_SUFFIX = ".fileprovider";

    private final Context ctx;
    private volatile boolean checking = false;
    private volatile boolean available = false;
    private volatile int remoteCode = 0;
    private volatile String remoteName = "";
    private volatile String remoteUrl = "";
    /** none / checking / downloading / ready / error / grant (grant = the
     * update is downloaded and verified, but the unknown-sources grant is
     * missing, so no install prompt can be shown yet). */
    private volatile String state = "none";

    public UpdateChecker(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    // ---------- state for the panel (/api/state) ----------
    public String getState() { return state; }
    public boolean isAvailable() { return available; }
    public int getRemoteVersionCode() { return remoteCode; }
    public String getRemoteVersionName() { return remoteName; }
    public boolean isAutoUpdate() { return isAutoUpdateEnabled(ctx); }
    public void setAutoUpdate(boolean on) { setAutoUpdateEnabled(ctx, on); }

    // ---------- prefs (same "karaoke_prefs" file the activity uses) ----------
    public static boolean isAutoUpdateEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_update", true);
        } catch (Exception e) { return true; }
    }
    public static void setAutoUpdateEnabled(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_update", on).apply();
        } catch (Exception ignored) { }
    }
    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** This build's versionCode (from the installed package). */
    public static int ownVersionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info == null ? 0 : info.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** v1.0.27: called from the service watchdog (every 10 s, main thread).
     * The actual fetch runs on its own thread; this only gates the cadence. */
    public void maybeCheckPeriodic() {
        try {
            if (checking) return;
            if (!isAutoUpdate()) return;
            long last = prefs().getLong("update_last_check_ms", 0);
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
            check();
        } catch (Throwable ignored) { }
    }

    /** Kick off a check on a background thread (idempotent while one runs). */
    public void check() {
        synchronized (this) {
            if (checking) return;
            checking = true;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try { doCheck(); }
                catch (Throwable t) { fail("ngoai le: " + t); }
                finally { checking = false; }
            }
        }, "update-check").start();
    }

    private void doCheck() {
        if (!isAutoUpdate()) {
            StartupLog.log("Cap nhat: tat (tu cap nhat dang tat)");
            return;
        }
        // Mark the attempt now so the watchdog cannot fire a second check
        // while this one runs; on failure the stamp is moved back so the
        // next attempt comes in 30 min instead of 6 h (see fail()).
        prefs().edit().putLong("update_last_check_ms", System.currentTimeMillis()).apply();
        state = "checking";
        StartupLog.log("Cap nhat: kiem tra");
        String json = fetchText(VERSION_URL, 10000, 10000);
        if (json == null) { fail("khong tai duoc version.json"); return; }
        int code;
        String name;
        String apkUrl;
        try {
            JSONObject o = new JSONObject(json);
            code = o.optInt("versionCode", -1);
            name = o.optString("versionName", "");
            apkUrl = o.optString("apkUrl", "");
        } catch (Exception e) { fail("version.json loi: " + e); return; }
        if (code <= 0 || apkUrl == null || apkUrl.isEmpty()) { fail("version.json thieu truong"); return; }
        int own = ownVersionCode(ctx);
        if (code <= own) {
            available = false;
            remoteCode = 0;
            remoteName = "";
            remoteUrl = "";
            state = "none";
            StartupLog.log("Cap nhat: dang dung ban moi nhat (" + own + ")");
            return;
        }
        available = true;
        remoteCode = code;
        remoteName = name;
        remoteUrl = apkUrl;
        StartupLog.log("Cap nhat: co ban moi " + code + " " + name);
        // UPDATE upload #1 (found): once per version — the token caps at
        // 50 requests, so never upload on routine no-update checks.
        try {
            if (prefs().getInt("update_found_version", 0) != code) {
                LogUploader.send("UPDATE");
                prefs().edit().putInt("update_found_version", code).apply();
            }
        } catch (Throwable ignored) { }
        // Already downloaded + verified for this version? Re-prompt the
        // install (notification / direct intent) without re-downloading
        // and without another upload.
        File f = apkFile(code);
        if (prefs().getInt("update_downloaded_version", 0) == code
                && f.exists() && verifyApk(f, code, own)) {
            state = "ready";
            StartupLog.log("Cap nhat: da tai xong (ban " + code + ")");
            promptInstall();
            return;
        }
        download(code, apkUrl, own);
    }

    /** Download the APK to the app cache, verify it, then prompt install. */
    private void download(int code, String apkUrl, int own) {
        state = "downloading";
        StartupLog.log("Cap nhat: dang tai");
        deleteStaleApks(code);
        File tmp = new File(ctx.getCacheDir(), "karaoke-update-" + code + ".apk.tmp");
        File dst = apkFile(code);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(apkUrl).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "KaraokeYouTube/" + ownVersionCode(ctx));
            int http = c.getResponseCode();
            if (http != 200) { fail("tai APK HTTP " + http); return; }
            InputStream in = c.getInputStream();
            FileOutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
            os.close();
            in.close();
            StartupLog.log("Cap nhat: tai xong " + total + " byte");
            if (!tmp.renameTo(dst)) { fail("doi ten file tai ve that bai"); return; }
            if (!verifyApk(dst, code, own)) {
                try { dst.delete(); } catch (Exception ignored) { }
                fail("file loi (sai package hoac version)");
                return;
            }
            prefs().edit().putInt("update_downloaded_version", code).apply();
            state = "ready";
            StartupLog.log("Cap nhat: da tai xong");
            // UPDATE upload #2 (downloaded): once per version.
            try {
                if (prefs().getInt("update_dl_notified_version", 0) != code) {
                    LogUploader.send("UPDATE");
                    prefs().edit().putInt("update_dl_notified_version", code).apply();
                }
            } catch (Throwable ignored) { }
            promptInstall();
        } catch (Exception e) {
            try { tmp.delete(); } catch (Exception ignored) { }
            fail("tai APK LOI: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** v1.0.27: verify a downloaded APK before prompting install — package
     * must be vn.karaoke.youtube and its versionCode must be exactly the
     * one version.json announced (and newer than this build). Anything else
     * is deleted, never installed. */
    private boolean verifyApk(File f, int code, int own) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (info == null) return false;
            if (!"vn.karaoke.youtube".equals(info.packageName)) return false;
            if (info.versionCode != code) return false;
            if (info.versionCode <= own) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    private File apkFile(int code) {
        return new File(ctx.getCacheDir(), "karaoke-update-" + code + ".apk");
    }

    /** Drop update APKs of other versions (and stale .tmp files) so the
     * cache never accumulates a download per release. */
    private void deleteStaleApks(int keepCode) {
        try {
            File dir = ctx.getCacheDir();
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("karaoke-update-")
                        && !n.equals("karaoke-update-" + keepCode + ".apk")) {
                    try { f.delete(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    }

    private void fail(String msg) {
        StartupLog.log("Cap nhat: LOI " + msg);
        state = "error";
        // Retry in 30 min instead of 6 h: move the last-check stamp back.
        try {
            long retryAt = System.currentTimeMillis() - CHECK_INTERVAL_MS + RETRY_AFTER_ERROR_MS;
            prefs().edit().putLong("update_last_check_ms", retryAt).apply();
        } catch (Exception ignored) { }
    }

    /** Plain-HTTPS GET, whole body as a String (null on any failure).
     * Same pattern as LogUploader / RemoteServer.proxyStream. */
    private String fetchText(String urlStr, int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", "KaraokeYouTube/" + ownVersionCode(ctx));
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.toString();
        } catch (Exception e) {
            StartupLog.log("Cap nhat: fetch LOI: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** v1.0.27: show the install prompt for a verified download. Posts a
     * HIGH-PRIORITY notification ("Có bản Karaoke mới <name> — chạm để cài
     * đặt") whose tap fires the system installer, AND fires the installer
     * directly when the activity is in the foreground. If the user
     * dismisses it (or it fails), nothing nags: the download stays cached
     * and the next check / app start re-prompts (see doCheck()).
     * No grant yet -> no prompt at all (state "grant"): the one-time
     * dialog in KaraokeActivity is the ask path; the next check after the
     * grant re-prompts. */
    public void promptInstall() {
        try {
            if (!available || !"ready".equals(state)) return;
            File f = apkFile(remoteCode);
            if (!f.exists() || !verifyApk(f, remoteCode, ownVersionCode(ctx))) {
                StartupLog.log("Cap nhat: file chua san sang de cai");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26
                    && !ctx.getPackageManager().canRequestPackageInstalls()) {
                state = "grant";
                StartupLog.log("Cap nhat: chua cap quyen cai dat (can mo Cai dat)");
                return;
            }
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + PROVIDER_AUTHORITY_SUFFIX, f);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_UPDATE, "Karaoke — cập nhật",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 2, it,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHANNEL_UPDATE);
            else b = new Notification.Builder(ctx);
            Notification n = b.setContentTitle("Karaoke YouTube")
                    .setContentText("Có bản Karaoke mới " + remoteName + " — chạm để cài đặt")
                    .setSmallIcon(R.drawable.ic_app)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_UPDATE_ID, n);
            StartupLog.log("Cap nhat: da gui thong bao cai dat (" + remoteName + ")");
            if (KaraokeActivity.isForeground) {
                try {
                    ctx.startActivity(it);
                    StartupLog.log("Cap nhat: mo man hinh cai dat");
                } catch (Throwable t) {
                    StartupLog.log("Cap nhat: mo man hinh cai dat LOI: " + t);
                }
            }
        } catch (Throwable t) {
            StartupLog.log("Cap nhat: prompt LOI: " + t);
        }
    }

    /** v1.0.27: called from KaraokeActivity.onResume — the user may have
     * just granted unknown-sources in Settings, so if the state is "grant"
     * and the grant is now given, re-run the check at once (the cached
     * download is verified, not re-downloaded) instead of waiting up to
     * 6 h for the next periodic check. */
    public void recheckAfterGrant() {
        try {
            if (!"grant".equals(state)) return;
            if (Build.VERSION.SDK_INT >= 26
                    && !ctx.getPackageManager().canRequestPackageInstalls()) return;
            StartupLog.log("Cap nhat: da cap quyen cai dat, kiem tra lai");
            check();
        } catch (Throwable ignored) { }
    }
}
