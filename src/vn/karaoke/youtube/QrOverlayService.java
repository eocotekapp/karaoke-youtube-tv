package vn.karaoke.youtube;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Floating QR overlay — visible over ANY app and over the home screen/launcher.
 * WindowManager overlay (TYPE_APPLICATION_OVERLAY on API 26+, TYPE_PHONE below),
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL so it never steals D-pad focus.
 *
 * GATHIN ORIGINAL (measured from tvhome-new.apk, classes5.dex — read-only):
 * MesuHome.build() puts a white rounded card on the launcher home screen with
 *   - QR ImageView 132dp x 132dp  (const/16 132, MesuUi.dp(ctx,132))
 *   - card: white GradientDrawable, corner radius 12dp, padding 9dp (left/top/
 *     right) and 6dp (bottom), label "Karaoke" 16sp colour 0xFF14213A, centred
 *   - position: FrameLayout gravity 0x00800053 = BOTTOM|START (bottom-LEFT),
 *     margins 16dp left / 16dp bottom; refreshed every 45 s
 *   - bitmap: MesuUi.qrBitmap(url, px) — quiet zone 2 modules each side
 * (The other Gathin QR, in the MesuSettings activity, is 190dp centred in a
 * settings screen — that is NOT the floating one, so it is not used here.)
 * This overlay reproduces that card's styling as its base size.
 * v1.0.20 (user, 2026-11-28): base size is now 96dp (not the Gathin 132dp)
 * and the anchor is TOP-LEFT (not bottom-left) — the user wants the QR
 * smaller and at the top-left corner of the screen.
 *
 * HOME-SCREEN 3x RULE (user, 2026-11-27): the card is 3x the base size
 * (QR 288dp) ONLY while the foreground app is the launcher/home screen; in
 * any other app it returns to the base size (QR 96dp). Foreground
 * detection: UsageStatsManager first (needs usage-access grant, asked for on
 * the first-run screen), ActivityManager.getRunningAppProcesses as fallback.
 *
 * TWO STATES ONLY (user rule, 2026-11-27):
 *   - Pinggy connected  -> QR visible, showing the CURRENT Pinggy public URL.
 *   - Pinggy down / reconnecting -> QR HIDDEN completely (never the LAN URL).
 * The QR updates automatically whenever Pinggy reconnects with a new URL.
 *
 * Touch: tap toggles a manual size override (flips large/small; auto mode
 * resumes when the foreground app changes); drag moves it to any corner;
 * long-press hides it until the next Pinggy URL change. On/off toggle in prefs
 * ("karaoke_overlay" / "overlay_enabled", also via /api/overlay).
 * QR is drawn by the hand-rolled QrMatrix encoder — no QR library bundled.
 */
public class QrOverlayService extends Service {
    public static final String PREFS = "karaoke_overlay";

    // --- Gathin original, MesuHome.build() (see class doc) ---
    // v1.0.20: base QR is 96dp (was the Gathin 132dp), top-left anchor.
    private static final int GATHIN_QR_DP = 96;
    private static final int CARD_PAD_SIDE_DP = 4; // v1.0.17: was 9 — white bg too wide
    private static final int CARD_PAD_BOTTOM_DP = 3; // v1.0.17: was 6
    private static final int CARD_RADIUS_DP = 12;
    private static final int MARGIN_DP = 16;
    private static final int LABEL_SP = 16;
    private static final int LABEL_COLOR = 0xFF14213A;
    private static final int HOME_SCALE = 3; // 96dp -> 288dp on the home screen

    private static final long POLL_MS = 1500; // home detection + URL refresh

    private WindowManager wm;
    private LinearLayout card;
    private ImageView qrView;
    private TextView label;
    private WindowManager.LayoutParams params;
    private Handler handler;
    private String currentUrl = "";
    private boolean userHidden = false;
    private boolean added = false;
    private boolean anchored = true; // top-left until the user drags
    private Boolean manualLarge = null; // null = auto (home detection drives size)
    private String lastForegroundPkg = null;
    private int currentScale = 1;
    private int screenW, screenH;
    private Set<String> launcherPkgs = new HashSet<String>();

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

    /** True if the user granted usage access (needed for reliable home detection). */
    public static boolean hasUsageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate() {
        super.onCreate();
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
        launcherPkgs = resolveLauncherPackages();

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

        label = new TextView(this);
        label.setText("Karaoke");
        label.setTextColor(LABEL_COLOR);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP);
        label.setGravity(Gravity.CENTER);
        // v1.0.17: pull the label tight under the QR (same as the in-activity card).
        label.setIncludeFontPadding(false);
        label.setLineSpacing(0, 1.0f);
        card.addView(label);

        card.setVisibility(View.GONE); // hidden until a Pinggy URL exists
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(GATHIN_QR_DP + 2 * CARD_PAD_SIDE_DP),
                dp(GATHIN_QR_DP + CARD_PAD_SIDE_DP + CARD_PAD_BOTTOM_DP + 24),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(MARGIN_DP);
        params.y = dp(MARGIN_DP); // top-left, 16dp margins (v1.0.20)
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
                            params.x = downX + dx;
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
                            // tap: manual size override (flips large/small);
                            // auto mode resumes when the foreground app changes
                            manualLarge = !(currentScale == HOME_SCALE);
                            applySize(manualLarge ? HOME_SCALE : 1);
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
        applySize(1); // Gathin base size until home detection says otherwise
        handler = new Handler(Looper.getMainLooper());
        handler.post(tick);
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
        // Foreground change clears the manual size override -> back to auto.
        String fg = foregroundPackage();
        if (fg != null && !fg.equals(lastForegroundPkg)) {
            lastForegroundPkg = fg;
            manualLarge = null;
        }
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
            Bitmap bmp = QrCode.bitmap(url, Math.max(512, dp(GATHIN_QR_DP * currentScale)));
            if (bmp != null) {
                qrView.setImageBitmap(bmp);
                StartupLog.log("QR noi: hien link " + url);
            }
            else { card.setVisibility(View.GONE); return; }
        }
        if (userHidden) { card.setVisibility(View.GONE); return; }
        card.setVisibility(View.VISIBLE);
        int want = targetScale();
        if (want != currentScale) applySize(want);
    }

    /** 3x only on the home screen; base Gathin size in any other app. */
    private int targetScale() {
        if (manualLarge != null) return manualLarge ? HOME_SCALE : 1;
        String fg = lastForegroundPkg != null ? lastForegroundPkg : foregroundPackage();
        if (fg != null && launcherPkgs.contains(fg)) return HOME_SCALE;
        return 1;
    }

    /** Resize the whole card (QR + padding + label scale together, Gathin ratio). */
    private void applySize(int scale) {
        if (card == null) return;
        currentScale = scale;
        int qr = GATHIN_QR_DP * scale;
        card.setPadding(dp(CARD_PAD_SIDE_DP * scale), dp(CARD_PAD_SIDE_DP * scale),
                dp(CARD_PAD_SIDE_DP * scale), dp(CARD_PAD_BOTTOM_DP * scale));
        GradientDrawable bg = (GradientDrawable) card.getBackground();
        if (bg != null) bg.setCornerRadius(dp(CARD_RADIUS_DP * scale));
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) qrView.getLayoutParams();
        lp.width = dp(qr);
        lp.height = dp(qr);
        qrView.setLayoutParams(lp);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP * scale);
        int w = dp((GATHIN_QR_DP + 2 * CARD_PAD_SIDE_DP) * scale);
        params.width = w;
        // Measure the real card height (QR + label + padding) before placing it.
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(card.getMeasuredHeight(), dp(qr));
        params.height = h;
        if (anchored) { // top-left anchor, 16dp margins (v1.0.20)
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

    /** Packages that can be the home screen (launcher). Resolved once. */
    private Set<String> resolveLauncherPackages() {
        Set<String> out = new HashSet<String>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            if (list != null) for (ResolveInfo ri : list) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Foreground package: UsageStatsManager first, running-process list fallback. */
    private String foregroundPackage() {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                if (hasUsageAccess(this)) {
                    UsageStatsManager usm =
                            (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                    if (usm != null) {
                        long now = System.currentTimeMillis();
                        List<UsageStats> stats = usm.queryUsageStats(
                                UsageStatsManager.INTERVAL_DAILY, now - 60000, now);
                        String best = null;
                        long bestTime = 0;
                        if (stats != null) for (UsageStats s : stats) {
                            if (s.getLastTimeUsed() > bestTime) {
                                bestTime = s.getLastTimeUsed();
                                best = s.getPackageName();
                            }
                        }
                        if (best != null) return best;
                    }
                }
            } catch (Exception ignored) { }
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        return p.processName;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        if (added && card != null) {
            try { wm.removeView(card); } catch (Exception ignored) { }
        }
        added = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
