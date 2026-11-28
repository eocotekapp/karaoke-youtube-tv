package vn.karaoke.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * Foreground service while playing. Owns MesuPlayer, LibraryState,
 * RemoteServer (8792) and the Pinggy tunnel. Clean rewrite.
 * No MesuGuard/MesuBoot handshake — revival is BootReceiver + this service.
 */
public class PlayerService extends Service {
    private static final int NOTIF_ID = 8792;
    private static final String CHANNEL = "karaoke_playback";
    // v1.0.23: full-screen-intent fallback (see playNow()). A different
    // notification id so it never clobbers the foreground-service one.
    private static final int NOTIF_FS_ID = 8793;
    private static final String CHANNEL_FS = "karaoke_fullscreen";

    private static PlayerService instance;
    public static PlayerService getInstance() { return instance; }

    private MesuPlayer player;
    private LibraryState library;
    private RemoteServer server;
    private PinggyTunnel tunnel;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long sleepDeadlineMs = 0;
    private final Runnable sleepCheck = new Runnable() {
        @Override public void run() {
            if (sleepDeadlineMs > 0 && System.currentTimeMillis() >= sleepDeadlineMs) {
                sleepDeadlineMs = 0;
                if (player != null) player.stop();
                Toast.makeText(PlayerService.this, "Hết giờ hẹn — đã dừng phát", Toast.LENGTH_SHORT).show();
            } else if (sleepDeadlineMs > 0) {
                handler.postDelayed(this, 15000);
            }
        }
    };
    // Auto-advance when the current video ends (poll PX state, cheap).
    // Stall watchdog (v1.0.12): the old stage JS had watchStall() which
    // advanced on -1 (error) or 3 (buffering); the bare v1.0.11 stage has
    // no JS anymore, so the service does it here. A track stuck buffering
    // or errored for ~30 s (10 consecutive 3 s polls) is skipped exactly
    // like an ended track. Paused (2) never counts as a stall.
    private int stallPolls = 0;
    private final Runnable endCheck = new Runnable() {
        @Override public void run() {
            try {
                if (player != null) {
                    String st = player.getState();
                    int code = Integer.parseInt(st.split(",")[0]);
                    if (code == 0) { // ended
                        stallPolls = 0;
                        StartupLog.log("Bai vua het, chuyen bai ke");
                        LibraryState.Item n = library.next();
                        if (n != null) playNow(n);
                        else player.stop();
                    } else if ((code == 3 || code == -1) && library != null && library.current() != null) {
                        stallPolls++;
                        if (stallPolls >= 10) { // ~30 s stuck
                            stallPolls = 0;
                            StartupLog.log("Bai ket/loi, chuyen bai ke");
                            LibraryState.Item n = library.next();
                            if (n != null) playNow(n);
                            else player.stop();
                        }
                    } else {
                        stallPolls = 0;
                    }
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        StartupLog.log(this, "PlayerService onCreate");
        library = new LibraryState(this);
        player = new MesuPlayer(this);
        server = new RemoteServer(this, library, player);
        server.setService(this);
        tunnel = new PinggyTunnel(this, new PinggyTunnel.Listener() {
            @Override public void onUrl(String url) { updateNotification(); }
            @Override public void onState(String state) { updateNotification(); }
        });
        server.setTunnel(tunnel);
        server.start();
        StartupLog.log(this, "RemoteServer.start() goi xong, running=" + server.isRunning());
        tunnel.start();
        StartupLog.log(this, "PinggyTunnel.start() goi xong");
        // Anti-Doze / anti-kill: hold locks for as long as the server + tunnel run.
        // Foreground service (START_STICKY + ongoing notification, mediaPlayback)
        // is what exempts the app from Doze; the locks keep CPU + Wi-Fi alive
        // when the screen is off / the box idles for hours.
        // Locks are best-effort: on some vendor ROMs acquiring them throws,
        // and that used to kill the whole service before startForeground().
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "karaoke:playback");
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) { CrashHandler.log(this, e); }
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "karaoke:wifi");
                wifiLock.acquire();
            }
        } catch (Exception e) { CrashHandler.log(this, e); }
        try {
            startForeground(NOTIF_ID, buildNotification("Karaoke YouTube đang chạy"));
            StartupLog.log(this, "startForeground OK");
        } catch (Exception e) {
            StartupLog.log(this, "startForeground LOI: " + e);
            CrashHandler.log(this, e);
            throw new RuntimeException(e);
        }
        handler.postDelayed(endCheck, 3000);
        handler.postDelayed(watchdog, 10000);
        // Floating QR overlay (Pinggy URL only) — independent of the activity.
        // Only start it when overlay permission is granted: starting it
        // without permission made it stop without startForeground() and
        // Android killed the whole app (RemoteServiceException, 2026-11-27).
        if (QrOverlayService.isEnabled(this) && QrOverlayService.canShow(this)) {
            Intent oi = new Intent(this, QrOverlayService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(oi);
            else startService(oi);
        }
    }

    // Watchdog: revive RemoteServer if it died, revive the Pinggy session if
    // it dropped without the tunnel's own reconnect loop catching it.
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                if (server != null && !server.isRunning()) server.start();
                if (tunnel != null && tunnel.isEnabled()
                        && "off".equals(tunnel.getState())) tunnel.start();
            } catch (Exception ignored) { }
            handler.postDelayed(this, 10000);
        }
    };

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Self-revival: if the user swipes the app away, restart the service.
        Intent restart = new Intent(getApplicationContext(), PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart);
            else startService(restart);
        } catch (Exception ignored) { }
        super.onTaskRemoved(rootIntent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            if (player != null) player.stop();
        }
        return START_STICKY;
    }

    public void onStartedPlaying() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        updateNotification();
    }

    // v1.0.23: delayed bring-to-front check (see playNow()). One reusable
    // Runnable; pendingFsTitle carries the track name for the notification.
    private String pendingFsTitle = "";
    private final Runnable bringToFrontCheck = new Runnable() {
        @Override public void run() {
            try {
                if (KaraokeActivity.isForeground) {
                    StartupLog.log("Mo app: da len, khong can full-screen intent");
                    return;
                }
                StartupLog.log("Mo app: chua len, dung full-screen intent");
                postFullScreenNotification(pendingFsTitle);
            } catch (Throwable t) {
                StartupLog.log("Mo app: that bai: " + t);
            }
        }
    };

    public void playNow(LibraryState.Item it) {
        if (it == null) return;
        String title = it.title == null ? "" : it.title;
        // v1.0.21: the video renders to the SurfaceView inside KaraokeActivity.
        // If the user is on the home screen / another app, the activity is
        // not in the foreground, so there is NO surface at all — the track
        // plays audio-only (user report 2026-11-28 08:19: QR outside, on home
        // or any other app, play a video -> no video, audio only). Bring the
        // activity to the front before starting playback so its SurfaceView
        // exists and the video shows. The activity is singleTask, so this
        // reuses the existing instance (onNewIntent/onResume re-attaches the
        // surface); if it was finished, a fresh instance is created and its
        // onCreate loads the stage as usual.
        //
        // AUDIT 2026-11-28 (v1.0.23) — why v1.0.21 did NOT work on the FPT
        // Play 650, read end to end (RemoteServer /api/playnow -> playItem
        // -> playNow -> startActivity -> onResume/attachSurface -> player):
        //  1. startActivity() from a background service can fail SILENTLY.
        //     On API 30 the system drops a blocked background activity start
        //     WITHOUT throwing (ActivityManager only logs it), so the v1.0.21
        //     try/catch could not see it: the log said "Dua Karaoke len
        //     truoc khi phat" and playback went on audio-only. The
        //     SYSTEM_ALERT_WINDOW exemption is the theory, but on this
        //     operator box it evidently does not take effect — and whether
        //     the permission was actually granted at that moment cannot be
        //     confirmed from the code (the overlay runs, but its grant state
        //     is never logged). The log channel was dead at the same time
        //     (webhook 429), so the "Dua Karaoke..." line was never seen.
        //  2. Race (secondary, not the cause): playNow() calls player.play()
        //     immediately after startActivity(); resolve takes seconds and
        //     ExoPlayer starts decoding with surface=null (cleared in
        //     surfaceDestroyed on exit). ensureSurface() re-attaches later
        //     and ExoPlayer renders once a surface is set — so this alone
        //     cannot explain permanent audio-only.
        //  3. The surface path itself is SOUND (verified): surfaceDestroyed
        //     -> ensureSurface(null); surfaceCreated / surfaceChanged /
        //     onResume -> attachSurface() -> ensureSurface(surface), with a
        //     500 ms x 20 retry when the service/surface is not ready yet;
        //     video rendered fullscreen in v1.0.12 whenever the activity was
        //     in front. No gap found — left untouched.
        //  4. isForeground is correct (true in onResume, false in onPause /
        //     onDestroy). The stage status overlay is a bare TextView (no
        //     opaque background) and the video plays fine while the app is
        //     open, so it is not covering the picture either.
        // CONCLUSION: the single point of failure is step 1. The fix below
        // keeps the startActivity attempt as the FIRST try and adds a
        // second, independent mechanism — a full-screen intent — that does
        // not depend on the background-activity-start exemption at all.
        if (!KaraokeActivity.isForeground) {
            try {
                StartupLog.log("Mo app: startActivity dua Karaoke len truoc");
                Intent ai = new Intent(this, KaraokeActivity.class);
                ai.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(ai);
                StartupLog.log("Dua Karaoke len truoc khi phat");
            } catch (Throwable t) {
                StartupLog.log("Dua Karaoke len truoc LOI (am thanh van phat): " + t);
            }
            // v1.0.23 fallback: the start above can be dropped silently (see
            // audit). ~1 s later, check whether the activity actually came
            // to the front; if not, post a HIGH-PRIORITY notification with a
            // FULL-SCREEN INTENT — the mechanism Android provides for showing
            // UI from a background service (incoming calls). On an unlocked
            // TV box this normally surfaces as a heads-up the user opens with
            // one remote OK press, and on many TV firmwares it opens the
            // activity straight away. Never breaks playback: everything is
            // wrapped, audio keeps playing no matter what.
            pendingFsTitle = title;
            handler.removeCallbacks(bringToFrontCheck);
            handler.postDelayed(bringToFrontCheck, 1000);
        }
        player.play(it.id, title);
        onStartedPlaying();
    }

    /** v1.0.23: high-priority notification carrying a full-screen intent to
     * KaraokeActivity. Posted only when the activity is still not in the
     * foreground ~1 s after the plain startActivity attempt. */
    private void postFullScreenNotification(String title) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CHANNEL_FS, "Karaoke — mở video",
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);
            }
            Intent ai = new Intent(this, KaraokeActivity.class);
            ai.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(this, 1, ai,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_FS);
            else b = new Notification.Builder(this);
            String text = "Đang phát" + (title == null || title.isEmpty() ? "" : ": " + title)
                    + " — mở để xem video";
            Notification n = b.setContentTitle("Karaoke YouTube")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_app)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_FS_ID, n);
            StartupLog.log("Mo app: da gui full-screen intent");
        } catch (Throwable t) {
            StartupLog.log("Mo app: gui full-screen intent LOI: " + t);
        }
    }

    public void setSleepMinutes(int minutes) {
        if (minutes <= 0) { sleepDeadlineMs = 0; handler.removeCallbacks(sleepCheck); return; }
        sleepDeadlineMs = System.currentTimeMillis() + minutes * 60L * 1000L;
        StartupLog.log("Hen gio tat: " + minutes + " phut");
        handler.removeCallbacks(sleepCheck);
        handler.postDelayed(sleepCheck, 15000);
    }
    public int sleepMinutesLeft() {
        if (sleepDeadlineMs <= 0) return 0;
        long left = sleepDeadlineMs - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 60000) + 1;
    }

    public MesuPlayer getPlayer() { return player; }
    public LibraryState getLibrary() { return library; }
    public RemoteServer getServer() { return server; }
    public PinggyTunnel getTunnel() { return tunnel; }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Karaoke", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        Intent i = new Intent(this, KaraokeActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        String url = tunnel != null && !tunnel.getPublicUrl().isEmpty()
                ? tunnel.getPublicUrl() : (server == null ? "" : server.lanUrl());
        return b.setContentTitle("Karaoke YouTube")
                .setContentText(text + (url.isEmpty() ? "" : " — " + url))
                .setSmallIcon(R.drawable.ic_app)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification("Karaoke YouTube đang chạy"));
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(endCheck);
        handler.removeCallbacks(sleepCheck);
        handler.removeCallbacks(watchdog);
        if (tunnel != null) tunnel.stop();
        if (server != null) server.stop();
        if (player != null) player.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
