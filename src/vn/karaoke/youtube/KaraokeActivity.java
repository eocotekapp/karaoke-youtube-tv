package vn.karaoke.youtube;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    // v1.0.13: in-activity QR card (Gathin original qrCard) — visible on the
    // TV screen itself, no permission needed. Pinggy URL only; hidden while
    // the tunnel is down/reconnecting. Polled every 2 s via stageHandler.
    private LinearLayout qrCard;
    private ImageView qrImage;
    private String currentQrUrl = "";
    private final Runnable qrPoll = new Runnable() {
        @Override public void run() {
            try { refreshQrCard(); }
            catch (Throwable t) { StartupLog.log(KaraokeActivity.this, "QR man hinh LOI: " + t); }
            stageHandler.postDelayed(this, 2000);
        }
    };

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
        try { startPlayerService(); StartupLog.log(this, "startPlayerService() goi xong"); attachSurface(); }
        catch (Exception e) { StartupLog.log(this, "startPlayerService LOI: " + e); CrashHandler.log(this, e); }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF09090D);

        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { attachSurface(); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { attachSurface(); }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                // v1.0.14: the SurfaceView is gone when the user exits the app.
                // Clear the player's video surface so a dead surface never
                // kills the renderer on the next play (log 2026-11-28 07:06:
                // even 640x360 failed after exit). surfaceCreated/onResume
                // re-attach the fresh surface.
                try {
                    PlayerService s = PlayerService.getInstance();
                    if (s != null) s.getPlayer().ensureSurface(null);
                } catch (Throwable t) {
                    StartupLog.log(KaraokeActivity.this, "Surface huy LOI: " + t);
                }
            }
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
                stageErrorRetries = 0;
                StartupLog.log(KaraokeActivity.this, "onPageFinished: " + url);
                hideStageStatus();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    StartupLog.log(KaraokeActivity.this, "onReceivedError: " + request.getUrl() + " / " + error.getDescription());
                    if (stageErrorRetries < 3) {
                        stageErrorRetries++;
                        showStageStatus("Tai giao dien loi, dang thu lai lan " + stageErrorRetries + "/3…", false);
                        stageHandler.postDelayed(new Runnable() {
                            @Override public void run() { loadStageWhenReady(); }
                        }, 1500);
                        return;
                    }
                    LogUploader.send(KaraokeActivity.this, "STAGE-ERROR");
                    showStageStatus("KHONG TAI DUOC GIAO DIEN\n" + request.getUrl()
                            + "\nLoi: " + error.getDescription()
                            + "\n\nNhan OK tren remote de thu lai.", true);
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    StartupLog.log(KaraokeActivity.this, "onReceivedHttpError: " + request.getUrl() + " HTTP " + errorResponse.getStatusCode());
                    if (stageErrorRetries < 3) {
                        stageErrorRetries++;
                        showStageStatus("Tai giao dien loi, dang thu lai lan " + stageErrorRetries + "/3…", false);
                        stageHandler.postDelayed(new Runnable() {
                            @Override public void run() { loadStageWhenReady(); }
                        }, 1500);
                        return;
                    }
                    LogUploader.send(KaraokeActivity.this, "STAGE-ERROR");
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
        // v1.0.13: QR card inside the Activity (Gathin qrCard) — added after
        // the WebView so it sits on top; small corner card, no permission.
        qrCard = buildQrCard();
        root.addView(qrCard);
        setContentView(root);

        loadStageWhenReady();
        maybePromptOverlay(); // v1.0.14: one-time overlay-permission prompt
        stageHandler.post(qrPoll); // in-activity QR card refresh (2 s)
        // v1.0.5: the app now sends its startup log to Mesu by itself
        // (LogUploader, webhook.site) ~6 s after launch — no photo needed.
        stageHandler.postDelayed(new Runnable() {
            @Override public void run() { LogUploader.send(KaraokeActivity.this, "STARTUP"); }
        }, 6000);
        // Floating QR overlay (starts with the service; hides itself if no permission/URL).
        startQrOverlay();
    }

    /** Start the floating QR overlay when it is enabled and permitted. */
    private void startQrOverlay() {
        // Only start it when overlay permission is granted (see PlayerService).
        if (QrOverlayService.isEnabled(this) && QrOverlayService.canShow(this)) {
            try {
                Intent oi = new Intent(this, QrOverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(oi);
                else startService(oi);
            } catch (Exception e) { CrashHandler.log(this, e); }
        }
    }

    /**
     * v1.0.14: ONE-TIME overlay-permission prompt on launch. The floating QR
     * never started because the grant was never given and the user had to
     * hunt for the button (log 2026-11-28 07:06). Small native dialog,
     * dismissible, does NOT block the stage behind it. Asked once ever —
     * either choice marks it asked; the phone panel's "Cấp quyền" button
     * stays as the manual path.
     */
    private void maybePromptOverlay() {
        try {
            if (QrOverlayService.canShow(this)) return; // already granted
            SharedPreferences prefs = getSharedPreferences("karaoke_prefs", MODE_PRIVATE);
            if (prefs.getBoolean("overlay_prompt_asked", false)) return;
            prefs.edit().putBoolean("overlay_prompt_asked", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("QR nổi trên màn hình?")
                    .setMessage("Cho phép Karaoke vẽ QR lên trên các app khác và màn hình chính, để thoát app vẫn quét được.")
                    .setPositiveButton("Cho phép", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            try {
                                // Same form as /api/overlay?action=grant in RemoteServer.
                                Intent gi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(gi);
                            } catch (Exception e) {
                                StartupLog.log(KaraokeActivity.this, "Mo man hinh cap quyen LOI: " + e);
                            }
                        }
                    })
                    .setNegativeButton("Để sau", null)
                    .setCancelable(true)
                    .show();
        } catch (Throwable t) {
            StartupLog.log(this, "Overlay prompt LOI: " + t);
        }
    }

    private TextView stageStatus;
    private boolean stageErrorShown = false;
    private int stageLoadAttempts = 0;
    private int stageErrorRetries = 0;
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
                try {
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
                    LogUploader.send(KaraokeActivity.this, "STAGE-TIMEOUT");
                    showStageStatus("KHONG TAI DUOC GIAO DIEN\nServer 8792 chua chay sau 8 giay."
                            + "\n\nNhan OK tren remote de thu lai.", true);
                    return;
                }
                stageHandler.postDelayed(this, 300);
                } catch (Throwable t) {
                    StartupLog.log(KaraokeActivity.this, "stage poll LOI: " + t);
                    LogUploader.send(KaraokeActivity.this, "POLL-ERROR");
                }
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * v1.0.13: QR card inside the Activity, reproducing the Gathin original
     * qrCard (measured from tvhome-new.apk, classes5.dex — read-only):
     * white rounded card, corner radius 12dp, padding 9dp (left/top/right)
     * and 6dp (bottom), QR 132dp x 132dp, label "Karaoke" 16sp colour
     * 0xFF14213A centred, bottom-LEFT with 16dp margins. Same styling as
     * QrOverlayService's floating card, but this one lives in the Activity
     * itself so it needs no overlay permission — it is the QR the user scans
     * to open the phone panel. Hidden until a Pinggy URL exists.
     */
    private LinearLayout buildQrCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        // v1.0.17: tight card — padding 4dp sides/top, 3dp bottom (was 9/6);
        // the white background was too wide. QR stays 132dp.
        card.setPadding(dp(4), dp(4), dp(4), dp(3));
        qrImage = new ImageView(this);
        qrImage.setScaleType(ImageView.ScaleType.FIT_XY);
        qrImage.setLayoutParams(new LinearLayout.LayoutParams(dp(132), dp(132)));
        card.addView(qrImage);
        TextView label = new TextView(this);
        label.setText("Karaoke");
        label.setTextColor(0xFF14213A);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER);
        // v1.0.17: pull the label tight under the QR — no font padding, no
        // extra line spacing, so the card wastes no vertical space.
        label.setIncludeFontPadding(false);
        label.setLineSpacing(0, 1.0f);
        card.addView(label);
        card.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.START | Gravity.BOTTOM; // bottom-left, Gathin
        lp.leftMargin = dp(16);
        lp.bottomMargin = dp(16);
        card.setLayoutParams(lp);
        return card;
    }

    /** Pinggy URL only — hidden when the tunnel has no URL. Never the LAN URL.
     * v1.0.18: the QR encodes the PANEL URL (publicUrl + "/panel"), not the
     * bare root — "/" used to serve the bare transparent TV stage, which on
     * a phone browser renders as a pure white blank page. */
    private void refreshQrCard() {
        if (qrCard == null) return;
        String url = "";
        PlayerService svc = PlayerService.getInstance();
        if (svc != null && svc.getTunnel() != null) url = svc.getTunnel().getPublicUrl();
        if (url == null) url = "";
        if (url.isEmpty()) {
            currentQrUrl = "";
            qrCard.setVisibility(View.GONE);
            return;
        }
        url = url + "/panel";
        if (url.equals(currentQrUrl)) {
            if (qrCard.getVisibility() != View.VISIBLE) qrCard.setVisibility(View.VISIBLE);
            return;
        }
        Bitmap bmp = QrCode.bitmap(url, 512);
        if (bmp == null) { qrCard.setVisibility(View.GONE); return; }
        currentQrUrl = url;
        qrImage.setImageBitmap(bmp);
        qrCard.setVisibility(View.VISIBLE);
        StartupLog.log(this, "QR man hinh: hien link " + url);
    }

    private int surfaceRetryCount = 0;

    private void attachSurface() {
        PlayerService s = PlayerService.getInstance();
        if (s != null && surfaceView != null && surfaceView.getHolder().getSurface() != null) {
            s.getPlayer().ensureSurface(surfaceView.getHolder().getSurface());
            surfaceRetryCount = 0;
            StartupLog.log(this, "Surface gan xong");
            return;
        }
        // Service may not exist yet when the surface is created (slow box):
        // retry every 500 ms up to ~10 s so the video is never left unattached.
        if (surfaceRetryCount < 20) {
            surfaceRetryCount++;
            stageHandler.postDelayed(new Runnable() {
                @Override public void run() { attachSurface(); }
            }, 500);
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
        // v1.0.14: start the floating QR right after the user returns from
        // granting the overlay permission (prompt in onCreate / panel button).
        startQrOverlay();
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
        // Stage failed to load: OK on the remote retries instead of playing.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && stageErrorShown) {
            loadStageWhenReady();
            return true;
        }
        // Guard against an accidental double-Back exit (one stray second
        // press used to quit the app and look like a crash): first Back
        // only warns, a second one within 2 s exits.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
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
