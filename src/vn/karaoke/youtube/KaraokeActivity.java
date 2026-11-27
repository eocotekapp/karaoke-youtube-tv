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
        try { startPlayerService(); } catch (Exception e) { CrashHandler.log(this, e); }

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
        } catch (Throwable t) {
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
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new PlayerBridge(), "PX");
        webView.addJavascriptInterface(new ResolverBridge(), "MR");
        webView.addJavascriptInterface(new Bridge(), "KB");
        root.addView(webView);
        setContentView(root);

        webView.loadUrl("http://127.0.0.1:8792/stage");
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

    /**
     * First-run permission screen (shown once). Guides the user to grant:
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
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 30);
        TextView msg = new TextView(this);
        msg.setTextSize(20);
        msg.setText("Để Karaoke + QR chạy cả đêm không bị box tắt:\n\n" +
                "1. Bỏ tối ưu pin cho app — nếu không, Android sẽ cho app ngủ đông khi màn hình tắt, server 8792 và tunnel Pinggy sẽ chết.\n\n" +
                "2. Cho phép hiển thị trên app khác — để mã QR nổi trên mọi màn hình.\n\n" +
                "3. Bật tự khởi động trong cài đặt của box (nếu ROM có mục này) — để app tự chạy lại sau khi khởi động lại.\n\n" +
                "4. Cho phép xem app đang chạy — để mã QR tự to gấp 3 khi anh đang ở màn hình chính, và nhỏ lại như gốc khi mở app khác. Không cấp quyền này thì QR vẫn hiện, nhưng không tự to lên được.");
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
        done.setText("Xong");
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("karaoke_setup", Context.MODE_PRIVATE)
                        .edit().putBoolean("perm_screen_done", true).apply();
                if (permDialog != null) permDialog.dismiss();
            }
        });
        layout.addView(done);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        permDialog = new AlertDialog.Builder(this)
                .setTitle("Giữ Karaoke chạy cả đêm")
                .setView(scroll)
                .setCancelable(true)
                .create();
        permDialog.show();
    }

    private AlertDialog permDialog;

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
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    /** D-pad: centre = play/pause, left/right = prev/next, up/down = volume. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
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
