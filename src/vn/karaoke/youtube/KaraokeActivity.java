package vn.karaoke.youtube;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * KaraokeActivity — WebView (no layout XML), loads the local stage
 * http://127.0.0.1:8792/stage. JS bridges: PX (player), MR (resolver), KB.
 * Video renders to a SurfaceView behind the transparent WebView.
 * D-pad first, Leanback, large text. Clean rewrite.
 */
public class KaraokeActivity extends Activity {
    private WebView webView;
    private SurfaceView surfaceView;

    /** JS bridge for the stage page. */
    public class Bridge {
        @JavascriptInterface public void onReady() { }
        @JavascriptInterface public void onState(String s) { }
        @JavascriptInterface public void onError(String e) { }
    }

    /** PX — player bridge. */
    public class PlayerBridge {
        @JavascriptInterface public String getState() {
            PlayerService s = PlayerService.getInstance();
            return s == null ? "-1,0,0,0,0," : s.getPlayer().getState();
        }
        @JavascriptInterface public void play() { svcPlayerDo("resume"); }
        @JavascriptInterface public void pause() { svcPlayerDo("pause"); }
        @JavascriptInterface public void resume() { svcPlayerDo("resume"); }
        @JavascriptInterface public void stop() { svcPlayerDo("stop"); }
        @JavascriptInterface public void setVolume(float v) {
            PlayerService s = PlayerService.getInstance();
            if (s != null) s.getPlayer().setVolume(v);
        }
        private void svcPlayerDo(String what) {
            PlayerService s = PlayerService.getInstance();
            if (s == null) return;
            if ("pause".equals(what)) s.getPlayer().pause();
            else if ("resume".equals(what)) s.getPlayer().resume();
            else if ("stop".equals(what)) s.getPlayer().stop();
        }
    }

    /** MR — resolver bridge. */
    public class ResolverBridge {
        private final MesuResolver resolver = new MesuResolver();
        @JavascriptInterface public String resolve(String id) { return resolver.resolve(id); }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupLog.log(this, "Activity onCreate");
        try { startPlayerService(); StartupLog.log(this, "startPlayerService() goi xong"); }
        catch (Exception e) { StartupLog.log(this, "startPlayerService LOI: " + e); CrashHandler.log(this, e); }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF09090D);

        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { attachSurface(); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { attachSurface(); }
            @Override public void surfaceDestroyed(SurfaceHolder h) { }
        });
        root.addView(surfaceView);

        // WebView may be missing/disabled on operator boxes (FPT Play 650):
        // creating it then throws and used to kill the app on launch.
        // Show a readable error instead of crashing.
        try {
            webView = new WebView(this);
            StartupLog.log(this, "WebView tao xong");
        } catch (Throwable t) {
            StartupLog.log(this, "WebView LOI: " + t);
            CrashHandler.log(this, t);
            TextView err = new TextView(this);
            err.setTextSize(20);
            err.setTextColor(0xFFFFFFFF);
            err.setPadding(40, 40, 40, 40);
            err.setText("KHONG MO DUOC WEBVIEW\n\nMay nay thieu hoac da tat Android System WebView nen app Karaoke khong mo duoc giao dien.\n\nLoi: " + t.toString() + "\n\nHay bat lai WebView trong Cai dat > Ung dung, hoac cai Android System WebView tu Play Store roi mo lai app.");
            root.addView(err);
            setContentView(root);
            maybeShowPermissionScreen();
            return;
        }
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(0x00000000); // transparent: video shows through
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                StartupLog.log(KaraokeActivity.this, "onPageFinished: " + url);
                hideStageStatus();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    StartupLog.log(KaraokeActivity.this, "onReceivedError: " + request.getUrl() + " / " + error.getDescription());
                    showStageStatus("KHONG TAI DUOC GIAO DIEN\n" + request.getUrl()
                            + "\nLoi: " + error.getDescription()
                            + "\n\nNhan OK tren remote de thu lai.", true);
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    StartupLog.log(KaraokeActivity.this, "onReceivedHttpError: " + request.getUrl() + " HTTP " + errorResponse.getStatusCode());
                    showStageStatus("KHONG TAI DUOC GIAO DIEN\n" + request.getUrl()
                            + "\nHTTP " + errorResponse.getStatusCode()
                            + "\n\nNhan OK tren remote de thu lai.", true);
                }
            }
        });
        webView.addJavascriptInterface(new PlayerBridge(), "PX");
        webView.addJavascriptInterface(new ResolverBridge(), "MR");
        webView.addJavascriptInterface(new Bridge(), "KB");
        root.addView(webView);
        stageStatus = new TextView(this);
        stageStatus.setTextSize(18);
        stageStatus.setTextColor(0xFFFFFFFF);
        stageStatus.setPadding(60, 60, 60, 60);
        stageStatus.setText("Đang khởi động server Karaoke…");
        android.widget.ScrollView stageScroll = new android.widget.ScrollView(this);
        stageScroll.addView(stageStatus);
        root.addView(stageScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        loadStageWhenReady();
        maybeShowPermissionScreen();
        // Floating QR overlay (starts with the service; hides itself if no permission/URL).
        if (QrOverlayService.isEnabled(this)) {
            try {
                Intent oi = new Intent(this, QrOverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(oi);
                else startService(oi);
            } catch (Exception e) { CrashHandler.log(this, e); }
        }
    }

    private View permOverlay;

    /**
     * First-run permission screen. v1.0.2: NO MORE AlertDialog — on the
     * FPT box the dialog's "Xong" button sat below the fold inside a
     * ScrollView, so the user could not see how to get past it and pressed
     * Back (which then finished the activity — looked like a crash).
     * Now it is a plain overlay view on top of the stage with every button
     * visible on one 1080p screen, and Back just hides it.
     * Guides the user to grant:
     * (a) ignore battery optimisation — this is what keeps the karaoke server,
     *     Pinggy tunnel and floating QR alive overnight while the box idles;
     * (b) display over other apps — needed for the floating QR;
     * (c) autostart in the box's own settings (link to app settings; some
     *     vendor ROMs hide the autostart toggle elsewhere or not at all).
     */
    private void maybeShowPermissionScreen() {
        SharedPreferences prefs = getSharedPreferences("karaoke_setup", Context.MODE_PRIVATE);
        if (prefs.getBoolean("perm_screen_done", false)) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean usageOk = QrOverlayService.hasUsageAccess(this);
        if (batteryOk && overlayOk && usageOk) {
            prefs.edit().putBoolean("perm_screen_done", true).apply();
            return;
        }
        final FrameLayout root = (FrameLayout) findViewById(android.R.id.content);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFFF2F2F2);
        layout.setPadding(48, 32, 48, 32);
        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setTextColor(0xFF000000);
        title.setText("Giữ Karaoke chạy cả đêm");
        layout.addView(title);
        TextView msg = new TextView(this);
        msg.setTextSize(16);
        msg.setTextColor(0xFF000000);
        msg.setText("1. Bỏ tối ưu pin — không thì box cho app ngủ, server + Pinggy chết.\n"
                + "2. Hiển thị trên app khác — để mã QR nổi trên mọi màn hình.\n"
                + "3. Tự khởi động trong cài đặt box (nếu có) — để app tự chạy lại sau khi khởi động lại.\n"
                + "4. Xem app đang chạy — để QR tự to gấp 3 ở màn hình chính.");
        layout.addView(msg);
        Button b1 = new Button(this);
        b1.setText(batteryOk ? "✓ Đã bỏ tối ưu pin" : "1. Bỏ tối ưu pin cho app");
        b1.setEnabled(!batteryOk);
        b1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                }
            }
        });
        layout.addView(b1);
        Button b2 = new Button(this);
        b2.setText(overlayOk ? "✓ Đã cho phép hiển thị nổi" : "2. Cho phép hiển thị trên app khác");
        b2.setEnabled(!overlayOk);
        b2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) { }
            }
        });
        layout.addView(b2);
        Button b3 = new Button(this);
        b3.setText("3. Mở cài đặt app (tìm mục Tự khởi động)");
        b3.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) { }
            }
        });
        layout.addView(b3);
        Button b4 = new Button(this);
        b4.setText(usageOk ? "✓ Đã cho phép xem app đang chạy" : "4. Cho phép xem app đang chạy (QR to x3 ở màn hình chính)");
        b4.setEnabled(!usageOk);
        b4.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception ignored) { }
            }
        });
        layout.addView(b4);
        Button done = new Button(this);
        done.setText("XONG — VÀO KARAOKE");
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("karaoke_setup", Context.MODE_PRIVATE)
                        .edit().putBoolean("perm_screen_done", true).apply();
                hidePermOverlay();
            }
        });
        layout.addView(done);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0xCC000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(240, 90, 240, 90);
        wrap.addView(layout, lp);
        wrap.setFocusable(true);
        wrap.setFocusableInTouchMode(true);
        permOverlay = wrap;
        root.addView(wrap);
        done.requestFocus();
    }

    private void hidePermOverlay() {
        if (permOverlay != null) {
            ViewGroup parent = (ViewGroup) permOverlay.getParent();
            if (parent != null) parent.removeView(permOverlay);
            permOverlay = null;
        }
    }

    private TextView stageStatus;
    private boolean stageErrorShown = false;
    private int stageLoadAttempts = 0;
    private final android.os.Handler stageHandler = new android.os.Handler();

    /**
     * v1.0.3: the stage used to loadUrl("http://127.0.0.1:8792/stage") the
     * instant onCreate ran — but the server binds its socket on a background
     * thread inside the service, so on a slow box the WebView hit a dead port
     * and stayed black, with nothing on screen to explain it. Now we wait
     * until the server reports running (max ~8 s), load from the port it
     * actually bound, and keep a readable native status on screen until the
     * page paints; a load error shows the URL + error and OK retries.
     */
    private void loadStageWhenReady() {
        stageErrorShown = false;
        stageLoadAttempts = 0;
        showStageStatus("Đang khởi động server Karaoke…", false);
        StartupLog.log(this, "loadStageWhenReady: doi server...");
        stageHandler.post(new Runnable() {
            @Override public void run() {
                PlayerService s = PlayerService.getInstance();
                RemoteServer srv = s == null ? null : s.getServer();
                if (srv != null && srv.isRunning()) {
                    StartupLog.log(KaraokeActivity.this, "server running, port=" + srv.getPort() + " -> loadUrl " + srv.stageUrl());
                    if (webView != null) webView.loadUrl(srv.stageUrl());
                    return;
                }
                stageLoadAttempts++;
                if (stageLoadAttempts % 3 == 0) {
                    showStageStatus("Đang khởi động server Karaoke… (" + (stageLoadAttempts * 300 / 1000) + "s)", false);
                }
                if (stageLoadAttempts >= 27) { // ~8 s
                    StartupLog.log(KaraokeActivity.this, "server KHONG chay sau 8s (service=" + (s != null) + ")");
                    showStageStatus("KHONG TAI DUOC GIAO DIEN\nServer 8792 chua chay sau 8 giay."
                            + "\n\nNhan OK tren remote de thu lai.", true);
                    return;
                }
                stageHandler.postDelayed(this, 300);
            }
        });
    }

    private void showStageStatus(String text, boolean isError) {
        stageErrorShown = isError;
        if (stageStatus != null) {
            stageStatus.setVisibility(View.VISIBLE);
            // Always show the startup log under the message: if the app dies,
            // one photo of this screen tells exactly which step it died on.
            stageStatus.setText(text + "\n\n--- LOG KHOI DONG ---\n" + StartupLog.getText());
        }
    }

    private void hideStageStatus() {
        stageErrorShown = false;
        if (stageStatus != null) stageStatus.setVisibility(View.GONE);
    }

    private void attachSurface() {
        PlayerService s = PlayerService.getInstance();
        if (s != null && surfaceView != null && surfaceView.getHolder().getSurface() != null) {
            s.getPlayer().ensureSurface(surfaceView.getHolder().getSurface());
        }
    }

    private void startPlayerService() {
        Intent i = new Intent(this, PlayerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override protected void onResume() {
        super.onResume();
        attachSurface();
        if (webView != null) webView.loadUrl("javascript:if(window.refresh)refresh()");
    }

    @Override protected void onDestroy() {
        stageHandler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    /** D-pad: centre = play/pause, left/right = prev/next, up/down = volume. */
    private long lastBackPressMs = 0;

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back while the first-run permission overlay is up: just hide it
        // (and don't nag again) instead of finishing the activity — on the
        // FPT box that looked exactly like a launch crash.
        if (keyCode == KeyEvent.KEYCODE_BACK && permOverlay != null) {
            getSharedPreferences("karaoke_setup", Context.MODE_PRIVATE)
                    .edit().putBoolean("perm_screen_done", true).apply();
            hidePermOverlay();
            return true;
        }
        // Stage failed to load: OK on the remote retries instead of playing.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && stageErrorShown) {
            loadStageWhenReady();
            return true;
        }
        // Guard against an accidental double-Back exit (one stray second
        // press used to quit the app and look like a crash): first Back
        // only warns, a second one within 2 s exits.
        if (keyCode == KeyEvent.KEYCODE_BACK && permOverlay == null) {
            long now = System.currentTimeMillis();
            if (now - lastBackPressMs < 2000) return super.onKeyDown(keyCode, event);
            lastBackPressMs = now;
            android.widget.Toast.makeText(this, "Bấm Back lần nữa để thoát Karaoke", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        PlayerService s = PlayerService.getInstance();
        if (s != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: {
                    String st = s.getPlayer().getState();
                    if (st.startsWith("1,")) s.getPlayer().pause(); else s.getPlayer().resume();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    LibraryState.Item n = s.getLibrary().next();
                    if (n != null) s.playNow(n);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT: {
                    LibraryState.Item p = s.getLibrary().prev();
                    if (p != null) s.playNow(p);
                    return true;
                }
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    s.getPlayer().resume();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    s.getPlayer().pause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT: {
                    LibraryState.Item n = s.getLibrary().next();
                    if (n != null) s.playNow(n);
                    return true;
                }
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: {
                    LibraryState.Item p = s.getLibrary().prev();
                    if (p != null) s.playNow(p);
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
