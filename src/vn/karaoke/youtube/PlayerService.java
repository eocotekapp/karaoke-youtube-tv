package vn.karaoke.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    // v1.0.27: self-update (UpdateChecker) — owned by the service, started
    // in onCreate; the panel reads its state via getUpdateChecker().
    private UpdateChecker updateChecker;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long sleepDeadlineMs = 0;
    private final Runnable sleepCheck = new Runnable() {
        @Override public void run() {
            if (sleepDeadlineMs > 0 && System.currentTimeMillis() >= sleepDeadlineMs) {
                sleepDeadlineMs = 0;
                stopPlayer(); // v1.0.26: through the main-thread choke point
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
                        // v1.0.24: queue over -> stop + drop the overlay
                        // window (a black fullscreen overlay must never
                        // sit over the home screen when nothing plays).
                        else { stopPlayer(); if (videoOverlayAdded) hideVideoOverlay(); }
                    } else if ((code == 3 || code == -1) && library != null && library.current() != null) {
                        stallPolls++;
                        if (stallPolls >= 10) { // ~30 s stuck
                            stallPolls = 0;
                            StartupLog.log("Bai ket/loi, chuyen bai ke");
                            LibraryState.Item n = library.next();
                            if (n != null) playNow(n);
                            else stopPlayer();
                        }
                    } else {
                        stallPolls = 0;
                    }
                    // v1.0.24: overlay video follows the player state while
                    // the activity is in the background. Playing (1) with
                    // no overlay yet -> show it (covers resume-after-pause
                    // from the panel, which never goes through playNow).
                    // Paused (2) / idle-error (-1) -> remove it so a black
                    // fullscreen overlay never sits over the home screen
                    // when nothing is playing. Buffering (3) leaves it
                    // alone either way. Ended (0) is handled above.
                    if (code == 1 && !KaraokeActivity.isForeground && !videoOverlayAdded) {
                        showVideoOverlay();
                    } else if ((code == 2 || code == -1) && videoOverlayAdded) {
                        hideVideoOverlay();
                    }
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 3000);
        }
    };

    /** v1.0.35: a new fallback attempt (proxy retry / next candidate)
     * restarts the ~30 s stall clock so endCheck cannot skip the
     * track mid-fallback. Called from MesuPlayer's fallback paths. */
    public void resetStallPolls() { stallPolls = 0; }

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
        // v1.0.27: self-update — first check ~20 s after start (the service
        // just came up; the network/tunnel is still settling), then every
        // ~6 h via the watchdog (UpdateChecker.maybeCheckPeriodic).
        updateChecker = new UpdateChecker(this);
        handler.postDelayed(updateCheckStart, 20000);
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
    // v1.0.27: also gates the self-update check cadence (~6 h).
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                if (server != null && !server.isRunning()) server.start();
                if (tunnel != null && tunnel.isEnabled()
                        && "off".equals(tunnel.getState())) tunnel.start();
                if (updateChecker != null) updateChecker.maybeCheckPeriodic();
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
            stopPlayer(); // v1.0.26: through the main-thread choke point
        }
        return START_STICKY;
    }

    public void onStartedPlaying() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        updateNotification();
    }

    // v1.0.24: overlay video window (service-owned SurfaceView over ANY
    // screen). See showVideoOverlay() — the mechanism that cannot be
    // blocked by background-activity-start restrictions.
    private WindowManager videoOverlayWm;
    private SurfaceView videoOverlayView;
    private boolean videoOverlayAdded = false;
    private boolean videoOverlaySurfaceLive = false;

    // v1.0.25: Back-dismissed by the user (see the key handler in
    // showVideoOverlay()). While set, endCheck / onPause must NOT re-show
    // the overlay — Back really exits the video view. Cleared on the next
    // playNow() (new track), on panel resume (RemoteServer control), and
    // when the activity comes to the foreground (KaraokeActivity.onResume).
    // Volatile: set on the main thread (key handler), read on the main
    // thread (show) and on pool threads (playNow).
    private volatile boolean videoOverlayDismissedByUser = false;

    /** v1.0.25: clear the Back-dismissed flag (new track / panel resume /
     * app-open). Idempotent. */
    public void clearVideoOverlayDismissed() { videoOverlayDismissedByUser = false; }

    /**
     * v1.0.26: THE single choke point for every player-touching call that
     * originates off the main thread. WHY: RemoteServer answers HTTP on a
     * cached pool thread (e.g. "pool-2-thread-10") and its /api/control
     * actions called MesuPlayer.pause()/resume()/stop()/setVolume()/
     * seekTo() DIRECTLY on that thread — ExoPlayer 2.19.1 enforces
     * application-thread access and threw "Player is accessed on the wrong
     * thread" (proven live 2026-11-28 11:01: /api/control?action=toggle
     * and ?action=stop both answered {"error":"Player is accessed on the
     * wrong thread..."}, state stayed "1,0,0,0,0" — the panel pause/stop
     * buttons did NOTHING, so the user could not even close the old app
     * to install the new one). Same class as the v1.0.15 stateJson bug,
     * but in the CONTROL path.
     *
     * WHY playNow WORKED while toggle/stop threw (traced end to end, not
     * guessed): MesuPlayer.play() never touches ExoPlayer synchronously —
     * it sets fields, logs, spawns a resolver thread, and that thread
     * posts prepareFromCandidates() (setMediaSource/prepare/
     * setPlayWhenReady) to a main-looper Handler. Every real ExoPlayer
     * call in the play path lands on the main thread. pause()/resume()/
     * stop()/setVolume()/seekTo() call the ExoPlayer method synchronously
     * on the calling thread, so from a pool thread they throw. getState()
     * works from pool threads because v1.0.15 made it read
     * listener-maintained lastState only (read-only, never throws) — it
     * stays direct.
     *
     * MECHANISM: if already on the main looper, run inline. Otherwise log
     * "Dieu khien: <name> len main thread" (the proof line), post to the
     * main handler and wait up to 3 s for it to finish (pool threads may
     * block — they are a cached pool; the main thread never blocks here
     * because the inline path is taken). Every action is try/catch so a
     * player failure can never kill the HTTP reply. The wrappers below
     * (pausePlayer/resumePlayer/stopPlayer/setPlayerVolume/seekPlayer)
     * are the only way the HTTP layer and the service internals mutate
     * the player — RemoteServer, endCheck, sleepCheck, onStartCommand and
     * the overlay Back handler all go through them; KaraokeActivity's
     * D-pad / JS-bridge calls go through them too. Direct MesuPlayer
     * mutations remaining: MesuPlayer.play() itself (safe by construction,
     * see above — and playNow routes even that through here), the
     * service-null defensive fallback in RemoteServer.playItem()
     * (unreachable in practice: the service is set before server.start()),
     * ensureSurface() (surface attach — all callers are main-thread UI /
     * surface callbacks), getState()/getLastError() (read-only), and
     * release() in onDestroy (teardown, main thread).
     */
    public void playerAction(final String name, final Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            StartupLog.log("Dieu khien: " + name + " (da o main thread)");
            try { action.run(); }
            catch (Throwable t) { StartupLog.log("Dieu khien " + name + " LOI: " + t); }
            return;
        }
        StartupLog.log("Dieu khien: " + name + " len main thread (tu " + Thread.currentThread().getName() + ")");
        final CountDownLatch done = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override public void run() {
                try { action.run(); }
                catch (Throwable t) { StartupLog.log("Dieu khien " + name + " LOI: " + t); }
                finally { done.countDown(); }
            }
        });
        try { done.await(3000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** v1.0.26 wrappers — the only player mutations the HTTP layer and the
     * service internals use. Null-safe; each logs through playerAction. */
    public void pausePlayer() {
        playerAction("pause", new Runnable() {
            @Override public void run() { if (player != null) player.pause(); }
        });
    }
    public void resumePlayer() {
        playerAction("resume", new Runnable() {
            @Override public void run() { if (player != null) player.resume(); }
        });
    }
    public void stopPlayer() {
        playerAction("stop", new Runnable() {
            @Override public void run() { if (player != null) player.stop(); }
        });
    }
    public void setPlayerVolume(final float v) {
        playerAction("volume", new Runnable() {
            @Override public void run() { if (player != null) player.setVolume(v); }
        });
    }
    public void seekPlayer(final long ms) {
        playerAction("seek", new Runnable() {
            @Override public void run() { if (player != null) player.seekTo(ms); }
        });
    }

    // v1.0.24: PLAY-BG upload — delayed 2 s so the "Video overlay:" and
    // "Mo app:" lines land in the ring buffer before it is sent. One
    // Runnable; re-posting replaces the pending one (a second background
    // play within 2 s still uploads once).
    private final Runnable playBgUpload = new Runnable() {
        @Override public void run() {
            try { LogUploader.send("PLAY-BG"); }
            catch (Throwable t) { StartupLog.log("PLAY-BG LOI: " + t); }
        }
    };

    /** v1.0.24: is the overlay video WINDOW on screen (video visible over
     * home screen / other apps, or about to be)? Used by the QR shrink
     * condition in QrOverlayService. */
    public boolean isVideoOverlayShown() { return videoOverlayAdded; }

    /** v1.0.24: is the overlay SURFACE currently attached to the player?
     * Used by KaraokeActivity.surfaceDestroyed so it never clears a live
     * overlay surface when the activity surface dies after the overlay
     * already took over. */
    public boolean isVideoOverlaySurfaceLive() { return videoOverlaySurfaceLive; }

    /**
     * v1.0.24: show the video on a service-owned fullscreen overlay window.
     * WHY: v1.0.21/23 tried to bring KaraokeActivity to the front (plain
     * startActivity, then a full-screen intent). Both failed on the FPT Play
     * 650 — proven by the user's TV photo 2026-11-28 09:18 (home screen,
     * audio only, no notification visible). The app already holds
     * SYSTEM_ALERT_WINDOW and already runs an overlay service, so a
     * WindowManager overlay is the mechanism that cannot be blocked: the
     * SurfaceView lives in the SERVICE, not in the activity, and renders
     * over whatever is on screen. Idempotent; all WindowManager work runs
     * on the main handler (playNow is called from pool threads too).
     * The activity surface takes over when the activity returns (see
     * KaraokeActivity.attachSurface -> hideVideoOverlay), and endCheck
     * removes the window when playback pauses/stops/ends.
     */
    public void showVideoOverlay() {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (videoOverlayAdded) return;
                    // The activity may have come forward between the post
                    // and this run — then its surface owns the video.
                    if (KaraokeActivity.isForeground) return;
                    // v1.0.25: the user dismissed the overlay with Back —
                    // do NOT re-show until the next track / panel resume /
                    // app-open clears the flag. Silent return: endCheck
                    // calls this every 3 s, so logging here would flood
                    // the ring buffer; the dismiss itself is logged once
                    // in the Back handler ("Video overlay: bi tat...").
                    if (videoOverlayDismissedByUser) return;
                    if (videoOverlayWm == null) {
                        videoOverlayWm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    }
                    videoOverlayView = new SurfaceView(PlayerService.this);
                    videoOverlayView.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    // v1.0.25: Back-to-dismiss (user report 2026-11-28
                    // 09:47 — "tao bam Khong thoat duoc": the v1.0.24
                    // window was FLAG_NOT_FOCUSABLE, so no remote key
                    // ever reached it and there was no dismiss path at
                    // all; Home switches the foreground app but the
                    // overlay stays on top while the video plays). The
                    // SurfaceView is made focusable and takes the Back
                    // key: hide the overlay AND pause the player (user
                    // correction 2026-11-28 09:49 — "Thoat thi khong can
                    // tieng nua"). Pause uses the exact same path as the
                    // phone panel's pause button (MesuPlayer.pause()),
                    // so the player state stays consistent; no new stop
                    // mechanism is invented. Other keys return false
                    // (dropped — the video is display-only; the panel
                    // controls playback). Pressing play again from the
                    // panel resumes and the overlay shows again (the
                    // flag is cleared on panel resume).
                    videoOverlayView.setFocusable(true);
                    videoOverlayView.setFocusableInTouchMode(true);
                    videoOverlayView.setOnKeyListener(new View.OnKeyListener() {
                        @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                            if (keyCode == KeyEvent.KEYCODE_BACK
                                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                                try {
                                    videoOverlayDismissedByUser = true;
                                    StartupLog.log("Video overlay: an do Back");
                                    StartupLog.log("Video overlay: bi tat, khong hien lai");
                                    hideVideoOverlay();
                                    pausePlayer(); // v1.0.26: through the choke point (already main thread — runs inline)
                                } catch (Throwable t) {
                                    StartupLog.log("Video overlay: Back LOI: " + t);
                                }
                                return true;
                            }
                            return false;
                        }
                    });
                    videoOverlayView.getHolder().addCallback(new SurfaceHolder.Callback() {
                        @Override public void surfaceCreated(SurfaceHolder h) {
                            try {
                                // The activity may have returned while the
                                // window was being added — never steal its
                                // surface; the activity owns the video then.
                                if (KaraokeActivity.isForeground) {
                                    hideVideoOverlay();
                                    return;
                                }
                                if (player != null && h.getSurface() != null) {
                                    player.ensureSurface(h.getSurface());
                                    videoOverlaySurfaceLive = true;
                                    StartupLog.log("Video overlay: hien");
                                    // v1.0.25: window focus can land after
                                    // addView — re-assert it here so Back
                                    // reaches the key handler.
                                    try { videoOverlayView.requestFocus(); } catch (Throwable ignored) { }
                                }
                            } catch (Throwable t) {
                                StartupLog.log("Video overlay: gan surface LOI: " + t);
                            }
                        }
                        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                            try {
                                if (!KaraokeActivity.isForeground && player != null
                                        && h.getSurface() != null) {
                                    player.ensureSurface(h.getSurface());
                                    videoOverlaySurfaceLive = true;
                                }
                            } catch (Throwable t) {
                                StartupLog.log("Video overlay: doi surface LOI: " + t);
                            }
                        }
                        @Override public void surfaceDestroyed(SurfaceHolder h) {
                            // Only clear when the overlay surface was the
                            // live one. During an intentional hide,
                            // hideVideoOverlay() clears the flag FIRST, so
                            // this never clobbers the activity surface that
                            // just took over. On an unexpected loss (screen
                            // off etc.) the flag is still set: clear so the
                            // renderer never dies on a dead surface; the
                            // surface is re-attached when it comes back.
                            if (videoOverlaySurfaceLive) {
                                videoOverlaySurfaceLive = false;
                                try {
                                    if (player != null) player.ensureSurface(null);
                                } catch (Throwable ignored) { }
                                StartupLog.log("Video overlay: mat surface");
                            }
                        }
                    });
                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    // v1.0.25: FLAG_NOT_FOCUSABLE REMOVED — the v1.0.24
                    // window could never receive the remote Back key.
                    // The window is focusable now so Back reaches the
                    // SurfaceView key handler; FLAG_NOT_TOUCH_MODAL stays
                    // (touches outside still go to the window below).
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    lp.x = 0;
                    lp.y = 0;
                    videoOverlayWm.addView(videoOverlayView, lp);
                    videoOverlayAdded = true;
                    StartupLog.log("Video overlay: tao");
                    // v1.0.25: give the SurfaceView focus so Back reaches
                    // its key handler (the window is focusable now).
                    try { videoOverlayView.requestFocus(); } catch (Throwable ignored) { }
                    // v1.0.25: PLAY-BG upload on a REAL show transition
                    // (onPause / endCheck path had none — the GTA session
                    // 2026-11-28 proved the evidence stayed in the ring
                    // buffer because the track started in-foreground and
                    // the overlay only appeared later via onPause).
                    // Same reusable Runnable as playNow: re-posting
                    // replaces the pending one, so at most one upload.
                    // Only here (after the add) so a suppressed show
                    // (dismissed flag / activity foreground) uploads
                    // nothing and the token budget is not burned.
                    handler.removeCallbacks(playBgUpload);
                    handler.postDelayed(playBgUpload, 2000);
                    // The QR must stay ABOVE the video (it is how the user
                    // opens the panel): same-type windows stack by add
                    // order, so re-add the QR card on top of the video.
                    try {
                        QrOverlayService q = QrOverlayService.getInstance();
                        if (q != null) q.bringToFront();
                    } catch (Throwable ignored) { }
                } catch (Throwable t) {
                    StartupLog.log("Video overlay: tao LOI: " + t);
                }
            }
        });
    }

    /** v1.0.24: remove the overlay video window. Idempotent. The live flag
     * is cleared FIRST so the surfaceDestroyed callback fired by removeView
     * never clears the activity surface that just took over. */
    public void hideVideoOverlay() {
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (!videoOverlayAdded) return;
                    videoOverlaySurfaceLive = false;
                    if (videoOverlayWm != null && videoOverlayView != null) {
                        try { videoOverlayWm.removeView(videoOverlayView); }
                        catch (Throwable ignored) { }
                    }
                    videoOverlayView = null;
                    videoOverlayAdded = false;
                    StartupLog.log("Video overlay: an");
                } catch (Throwable t) {
                    StartupLog.log("Video overlay: an LOI: " + t);
                }
            }
        });
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
        // v1.0.24 CORRECTION — the v1.0.23 audit above was WRONG on one
        // point: /api/playnow does NOT go through playNow() at all.
        // RemoteServer.playItem() called player.play() DIRECTLY, so every
        // panel play bypassed this whole method — the v1.0.21 startActivity
        // AND the v1.0.23 full-screen intent never fired for the user's
        // exact test (exit app -> choose video in the panel -> audio
        // only). That, not the SAW exemption, is the primary cause of the
        // 2026-11-28 failures. playItem now routes through playNow()
        // (RemoteServer.java), so this method is the true single choke
        // point for every play start: panel, paste, next/prev, queue
        // advance, stall watchdog, D-pad. The audit's step-1 finding
        // (blocked background starts fail silently on API 30) still
        // stands as a secondary cause — which is why the overlay video
        // below is the primary mechanism now, not startActivity.
        if (!KaraokeActivity.isForeground) {
            // v1.0.25: a new track clears a previous Back-dismiss so the
            // overlay shows again for this track.
            videoOverlayDismissedByUser = false;
            // v1.0.24 PRIMARY: service-owned overlay video surface — the
            // video renders on a fullscreen WindowManager overlay over
            // the home screen / other apps. Not blocked by
            // background-activity-start restrictions (the app holds
            // SYSTEM_ALERT_WINDOW and already runs an overlay service).
            // Shown BEFORE player.play() so the surface exists when the
            // first frame is decoded.
            showVideoOverlay();
            // v1.0.24: PLAY-BG upload — the old blindness was that a
            // normal panel play fires no upload tag, so the "Mo app:"
            // evidence never left the box. Delayed 2 s so the "Video
            // overlay:" / "Mo app:" lines land in the ring first. Only
            // in the background case (normal in-app playback uploads
            // nothing) — the fresh token caps at 50 requests.
            handler.removeCallbacks(playBgUpload);
            handler.postDelayed(playBgUpload, 2000);
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
        // v1.0.26: even play() goes through the choke point — play() itself
        // is safe from any thread (it defers all ExoPlayer calls to the
        // main thread internally, see the playerAction comment), but the
        // invariant is "no HTTP-origin call touches MesuPlayer except
        // through playerAction()". From a pool thread this posts to main
        // and waits (play() returns at once — it only starts the resolver
        // thread); from the main thread (endCheck / D-pad) it runs inline.
        playerAction("play", new Runnable() {
            @Override public void run() { player.play(it.id, title); }
        });
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
    /** v1.0.27: the self-update checker (null until onCreate runs). */
    public UpdateChecker getUpdateChecker() { return updateChecker; }

    // v1.0.27: first update check shortly after the service starts (plain
    // HTTPS to raw.githubusercontent.com — no tunnel involved). The 6 h
    // cadence after that is gated by the watchdog (maybeCheckPeriodic).
    private final Runnable updateCheckStart = new Runnable() {
        @Override public void run() {
            try { if (updateChecker != null) updateChecker.check(); }
            catch (Throwable ignored) { }
        }
    };

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
        handler.removeCallbacks(playBgUpload);
        handler.removeCallbacks(bringToFrontCheck);
        handler.removeCallbacks(updateCheckStart); // v1.0.27
        hideVideoOverlay(); // best-effort: the window dies with the process anyway
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
