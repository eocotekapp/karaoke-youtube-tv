package vn.karaoke.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Floating QR overlay — visible over ANY app and over the home screen/launcher.
 * WindowManager overlay (TYPE_APPLICATION_OVERLAY on API 26+, TYPE_PHONE below),
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL so it never steals D-pad focus.
 *
 * GATHIN ORIGINAL (measured from tvhome-new.apk, classes5.dex — read-only):
 * MesuHome.build() puts a white rounded card on the launcher home screen with
 *   - QR ImageView 132dp x 132dp  (const/16 132, MesuUi.dp(ctx,132))
 *   - card: white GradientDrawable, corner radius 12dp, padding 9dp (left/top/
 *     right) and 6dp (bottom) — the "Karaoke" label was removed in v1.0.33
 *   - position: FrameLayout gravity 0x00800053 = BOTTOM|START (bottom-LEFT),
 *     margins 16dp left / 16dp bottom; refreshed every 45 s
 *   - bitmap: MesuUi.qrBitmap(url, px) — quiet zone 2 modules each side
 * (The other Gathin QR, in the MesuSettings activity, is 190dp centred in a
 * settings screen — that is NOT the floating one, so it is not used here.)
 * This overlay reproduces that card's styling as its base size.
 * v1.0.20 (user, 2026-11-28): base size is 96dp (not the Gathin 132dp)
 * and the anchor is TOP-LEFT (not bottom-left) — the user wants the QR
 * smaller and at the top-left corner of the screen.
 * v1.0.26 (user, 2026-11-28 — "Cai tv t co 300dp thoi" + "May dua a cai QR
 * to the"): base size is now 64dp, playing 48dp. 64dp = 128 physical px on
 * this 320dpi box, ~2.8px per module for the v5 panel URL — still scannable
 * from the couch, and half the Gathin original's 132dp.
 *
 * HOME-SCREEN 3x RULE — REMOVED in v1.0.26. It used to grow the card to 3x
 * (QR 288dp) while the launcher/home screen was in the foreground, via
 * UsageStatsManager home detection (asked for on a one-time prompt in
 * v1.0.24). The user's TV photo 2026-11-28 11:09 showed the card at ~0.32
 * of the screen width — he hates it ("May dua a cai QR to the") and has
 * asked for a SMALL QR twice already. The QR is now 64dp EVERYWHERE
 * including the home screen; the 3x branch, the home detection and the
 * usage-access prompt are all deleted, and PACKAGE_USAGE_STATS is gone
 * from the manifest.
 * v1.0.22: while a video is playing the QR shrinks to 48dp
 * instead of 64dp — the playing size wins over the tap toggle, so the
 * card covers less of the video.
 * v1.0.33 (user, 2026-11-28): label removed; anchor moved to TOP-RIGHT.
 *
 * TWO STATES ONLY (user rule, 2026-11-27):
 *   - Pinggy connected  -> QR visible, showing the CURRENT Pinggy public URL.
 *   - Pinggy down / reconnecting -> QR HIDDEN completely (never the LAN URL).
 * The QR updates automatically whenever Pinggy reconnects with a new URL.
 *
 * Touch: tap toggles small (64dp <-> 48dp); drag moves it to any corner;
 * long-press hides it until the next Pinggy URL change. On/off toggle in prefs
 * ("karaoke_overlay" / "overlay_enabled", also via /api/overlay).
 * QR is drawn by the hand-rolled QrMatrix encoder — no QR library bundled.
 */
public class QrOverlayService extends Service {
    public static final String PREFS = "karaoke_overlay";

    // --- Gathin original, MesuHome.build() (see class doc) ---
    // v1.0.20: base QR is 96dp (was the Gathin 132dp), top-left anchor.
    // v1.0.26: base QR is 64dp, playing 48dp (user: "Cai tv t co 300dp thoi").
    private static final int GATHIN_QR_DP = 64;
    // v1.0.22: while a video is playing the floating QR shrinks to 48dp
    // so it covers less of the video. Anywhere else it is the
    // 64dp base. The home-screen 3x rule is GONE (v1.0.26).
    private static final int PLAYING_QR_DP = 48;
    private static final int CARD_PAD_SIDE_DP = 4; // v1.0.17: was 9 — white bg too wide
    private static final int CARD_PAD_BOTTOM_DP = 3; // v1.0.17: was 6
    private static final int CARD_RADIUS_DP = 12;
    private static final int MARGIN_DP = 16;

    private static final long POLL_MS = 1500; // Pinggy URL refresh

    private WindowManager wm;
    private LinearLayout card;
    private ImageView qrView;
    private WindowManager.LayoutParams params;
    private Handler handler;
    private String currentUrl = "";
    private boolean userHidden = false;
    private boolean added = false;
    private boolean anchored = true; // top-left until the user drags
    private boolean manualSmall = false; // v1.0.26: tap toggles 64dp <-> 48dp
    private boolean playingShrunk = false; // v1.0.22: QR at 48dp while playing
    private int screenW, screenH;

    // v1.0.24: static instance so PlayerService can bring the QR back on
    // top after adding the fullscreen video overlay (same-type windows
    // stack by add order; the video would otherwise cover the QR).
    private static QrOverlayService instance;
    public static QrOverlayService getInstance() { return instance; }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("overlay_enabled", true);
    }
    /** Overlay permission granted (needed before starting the service at all). */
    public static boolean canShow(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }
    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("overlay_enabled", on).apply();
        Intent i = new Intent(ctx, QrOverlayService.class);
        if (on) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } else {
            ctx.stopService(i);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        // MUST call startForeground() FIRST, before anything else.
        // A service started with startForegroundService() has ~5 s to call
        // startForeground() — even when it is about to stop itself. The old
        // code checked overlay permission first and called stopSelf() without
        // startForeground(), and Android killed the WHOLE app process with
        // RemoteServiceException ("did not then call startForeground()").
        // That was the crash on the FPT Play 650 (log 2026-11-27 22:21).
        startForegroundQuiet();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf(); // no overlay permission yet — QR stays off, app lives
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(CARD_RADIUS_DP));
        card.setBackground(bg);
        card.setPadding(dp(CARD_PAD_SIDE_DP), dp(CARD_PAD_SIDE_DP),
                dp(CARD_PAD_SIDE_DP), dp(CARD_PAD_BOTTOM_DP));

        qrView = new ImageView(this);
        qrView.setScaleType(ImageView.ScaleType.FIT_XY);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(
                dp(GATHIN_QR_DP), dp(GATHIN_QR_DP)));
        card.addView(qrView);


        card.setVisibility(View.GONE); // hidden until a Pinggy URL exists
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(GATHIN_QR_DP + 2 * CARD_PAD_SIDE_DP),
                dp(GATHIN_QR_DP + CARD_PAD_SIDE_DP + CARD_PAD_BOTTOM_DP),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(MARGIN_DP);
        params.y = dp(MARGIN_DP); // top-right, 16dp margins (v1.0.33)
        card.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downX, downY;
            private long downTime;
            private boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        downX = params.x; downY = params.y;
                        downTime = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        if (moved) {
                            anchored = false;
                            params.x = downX - dx; // END gravity: x is the offset from the RIGHT edge, so dragging right (dx>0) must decrease x
                            params.y = downY + dy;
                            clampPosition();
                            try { wm.updateViewLayout(card, params); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        long dt = System.currentTimeMillis() - downTime;
                        if (!moved && dt > 600) {
                            // long-press: hide until the next Pinggy URL change
                            userHidden = true;
                            card.setVisibility(View.GONE);
                        } else if (!moved) {
                            // v1.0.26: tap toggles small (64dp <-> 48dp).
                            // The old manual 3x enlarge is gone with the
                            // home-screen 3x rule — the QR never exceeds
                            // 64dp anymore.
                            manualSmall = !manualSmall;
                            refreshQr();
                        }
                        return true;
                    }
                }
                return true;
            }
        });
        try {
            wm.addView(card, params);
            added = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }
        applySize(); // base 64dp (v1.0.26)
        handler = new Handler(Looper.getMainLooper());
        handler.post(tick);
    }

    /** v1.0.24: re-add the QR card so it sits ABOVE the fullscreen video
     * overlay (same TYPE_APPLICATION_OVERLAY windows stack in add order;
     * the video window is added later, so without this the video would
     * cover the QR). Called by PlayerService right after adding the video
     * window. Runs on this service's main handler; no-op when the card is
     * not added yet. */
    public void bringToFront() {
        if (handler == null) return;
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (!added || card == null || wm == null) return;
                    wm.removeView(card);
                    wm.addView(card, params);
                    StartupLog.log("QR noi: dua len tren video overlay");
                } catch (Throwable t) {
                    StartupLog.log("QR noi: dua len tren LOI: " + t);
                }
            }
        });
    }

    /** Low-priority foreground notification so the overlay survives (API 26+). */
    private void startForegroundQuiet() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("karaoke-qr", "QR Karaoke",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Giữ mã QR nổi trên màn hình");
                nm.createNotificationChannel(ch);
                Notification n = new Notification.Builder(this, "karaoke-qr")
                        .setContentTitle("Karaoke YouTube")
                        .setContentText("Mã QR đang hiển thị")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .build();
                startForeground(2, n);
            }
        } catch (Exception ignored) { }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // A tick must never kill the app: any failure here only stops
            // the QR refresh, it is never worth a crash.
            try { tickOnce(); } catch (Throwable t) { StartupLog.log(QrOverlayService.this, "QR tick LOI: " + t); }
            if (handler != null) handler.postDelayed(this, POLL_MS);
        }
    };

    private void tickOnce() {
        refreshQr();
    }

    /** Pinggy URL only. Empty URL -> hidden. Never the LAN URL.
     * v1.0.18: the QR encodes the PANEL URL (publicUrl + "/panel") — the
     * bare root serves the transparent TV stage, which is a white blank
     * page on a phone browser. */
    private void refreshQr() {
        if (card == null) return;
        String url = "";
        PlayerService svc = PlayerService.getInstance();
        if (svc != null && svc.getTunnel() != null) url = svc.getTunnel().getPublicUrl();
        if (url == null) url = "";
        if (url.isEmpty()) {
            currentUrl = "";
            card.setVisibility(View.GONE);
            return;
        }
        url = url + "/panel";
        boolean isNew = !url.equals(currentUrl);
        if (isNew) {
            currentUrl = url;
            userHidden = false; // a fresh tunnel re-shows the QR
            Bitmap bmp = QrCode.bitmap(url, Math.max(512, dp(currentQrDp())));
            if (bmp != null) {
                qrView.setImageBitmap(bmp);
                StartupLog.log("QR noi: hien link " + url);
            }
            else { card.setVisibility(View.GONE); return; }
        }
        if (userHidden) { card.setVisibility(View.GONE); return; }
        card.setVisibility(View.VISIBLE);
        // v1.0.26: shrink while playing, or tap-toggled small. No scale
        // anymore — the QR is 64dp normally, 48dp while shrunk.
        boolean shrink = isPlayingInForeground() || manualSmall;
        if (shrink != playingShrunk) {
            playingShrunk = shrink;
            applySize();
        }
    }

    /** Current QR size in dp: 48 while shrunk, 64 otherwise. */
    private int currentQrDp() {
        return playingShrunk ? PLAYING_QR_DP : GATHIN_QR_DP;
    }

    /** v1.0.22: shrink only when the video is actually visible — the
     * activity is in the foreground AND the player state is playing
     * (st==1 in "st,pos,dur,w,h,f"). Null-safe, never throws.
     * v1.0.24: also shrink while the video plays on the service-owned
     * overlay over the home screen / other apps — the user's v1.0.22 ask
     * was "QR smaller while a video is playing", the video can now play
     * in the background too. Sizes: 48dp (v1.0.26: was 64dp). */
    private boolean isPlayingInForeground() {
        try {
            PlayerService svc0 = PlayerService.getInstance();
            if (svc0 != null && svc0.isVideoOverlayShown()) return true;
            if (!KaraokeActivity.isForeground) return false;
            PlayerService svc = PlayerService.getInstance();
            if (svc == null || svc.getPlayer() == null) return false;
            String st = svc.getPlayer().getState();
            return st != null && st.startsWith("1,");
        } catch (Throwable t) { return false; }
    }

    /** Resize the whole card (QR + padding together, Gathin ratio).
     * v1.0.26: no scale anymore — the QR is 64dp normally, 48dp while
     * shrunk (playing, or tap-toggled small); padding/radius are the base
     * values either way. v1.0.33: no label anymore. */
    private void applySize() {
        if (card == null) return;
        int qr = currentQrDp();
        card.setPadding(dp(CARD_PAD_SIDE_DP), dp(CARD_PAD_SIDE_DP),
                dp(CARD_PAD_SIDE_DP), dp(CARD_PAD_BOTTOM_DP));
        GradientDrawable bg = (GradientDrawable) card.getBackground();
        if (bg != null) bg.setCornerRadius(dp(CARD_RADIUS_DP));
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) qrView.getLayoutParams();
        lp.width = dp(qr);
        lp.height = dp(qr);
        qrView.setLayoutParams(lp);
        int w = dp(qr + 2 * CARD_PAD_SIDE_DP);
        params.width = w;
        // Measure the real card height (QR + padding) before placing it.
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(card.getMeasuredHeight(), dp(qr));
        params.height = h;
        if (anchored) { // top-right anchor, 16dp margins (v1.0.33)
            params.x = dp(MARGIN_DP);
            params.y = dp(MARGIN_DP);
        } else {
            clampPosition();
        }
        if (added) {
            try { wm.updateViewLayout(card, params); } catch (Exception ignored) { }
        }
        if (!currentUrl.isEmpty()) {
            Bitmap bmp = QrCode.bitmap(currentUrl, Math.max(512, dp(qr)));
            if (bmp != null) qrView.setImageBitmap(bmp);
        }
    }

    private void clampPosition() {
        if (params.x < 0) params.x = 0;
        if (params.y < 0) params.y = 0;
        if (params.x > screenW - params.width) params.x = Math.max(0, screenW - params.width);
        if (params.y > screenH - params.height) params.y = Math.max(0, screenH - params.height);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null; // v1.0.24
        if (handler != null) handler.removeCallbacks(tick);
        if (added && card != null) {
            try { wm.removeView(card); } catch (Exception ignored) { }
        }
        added = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
