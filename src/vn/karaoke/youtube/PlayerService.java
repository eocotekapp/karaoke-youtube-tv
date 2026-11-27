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
    private final Runnable endCheck = new Runnable() {
        @Override public void run() {
            try {
                if (player != null) {
                    String st = player.getState();
                    int code = Integer.parseInt(st.split(",")[0]);
                    if (code == 0) { // ended
                        LibraryState.Item n = library.next();
                        if (n != null) playNow(n);
                        else player.stop();
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

    public void playNow(LibraryState.Item it) {
        if (it == null) return;
        player.play(it.id, it.title == null ? "" : it.title);
        onStartedPlaying();
    }

    public void setSleepMinutes(int minutes) {
        if (minutes <= 0) { sleepDeadlineMs = 0; handler.removeCallbacks(sleepCheck); return; }
        sleepDeadlineMs = System.currentTimeMillis() + minutes * 60L * 1000L;
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
