package vn.karaoke.youtube;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v1.0.36: YouTube sign-in with the USER'S OWN account session.
 *
 * How it works (no OAuth, no other app's credentials — ever):
 * the user signs into his own Google/YouTube account on Google's own
 * sign-in page, inside the app's WebView on the TV (KaraokeActivity's
 * sign-in WebView). The app never sees the password or 2FA. After
 * sign-in, the youtube.com session cookies are read from
 * android.webkit.CookieManager and stored ENCRYPTED (AndroidKeyStore
 * AES/GCM) + used as a plain Cookie header + SAPISIDHASH on InnerTube
 * calls — exactly the way yt-dlp authenticates with --cookies.
 *
 * HARD RULES (the v1.0.36 SmartTube attempt was blocked for this):
 * NEVER use/scrape/copy/hard-code/probe/embed any other app's OAuth
 * client_id/client_secret (incl. YouTube TV's / SmartTube's). NEVER call
 * the OAuth device-code or token endpoints. NEVER use the device-code
 * flow with anyone else's client. The ONLY credential here is the
 * user's own session cookie, captured from his own sign-in.
 *
 * Session source is pluggable: {@link SessionSource} is the seam — the
 * cookie session below is the only implementation today; a future
 * own-client device flow (only if the user supplies his OWN
 * client_id/secret) would implement SessionSource and plug in via
 * setSessionSource(). The device flow is NOT implemented here.
 *
 * SAPISIDHASH (verified against yt-dlp's public source,
 * yt_dlp/extractor/youtube/_base.py::_generate_sapisidhash_header,
 * corroborated by the yt-dlp-rust README and MeeTube §6):
 *   header = "SAPISIDHASH <unix_ts>_<sha1_hex>"
 *   sha1 input = "<unix_ts> <SAPISID> https://www.youtube.com"
 * Sent together with Cookie + "X-Origin: https://www.youtube.com"
 * (+ "X-Goog-AuthUser: 0", "X-Youtube-Bootstrap-Logged-In: true").
 * No "Authorization: Bearer ..." is ever sent — ANDROID player rejects
 * Bearer 400 (research §3), and yt-dlp uses cookies, not OAuth Bearer.
 *
 * Sign-in signal: the LOGIN_INFO cookie. MeeTube §6: the SAPISIDHASH
 * header requires LOGIN_INFO to be present. Cookie VALUES are never
 * logged — only cookie NAMES.
 */
public class YouTubeAuth {
    private static final String PREFS = "yt_auth";
    private static final String KEY_BLOB = "cookies_enc";
    private static final String KEY_ACCOUNT = "account_name";
    private static final String KEYSTORE_ALIAS = "karaoke_yt_auth";
    private static final String SAPISID_ORIGIN = "https://www.youtube.com";

    /** Pluggable session source. Cookie session now; a future own-client
     * device flow plugs in here (NOT implemented in v1.0.36). */
    public interface SessionSource {
        /** Short name for logs/status, e.g. "cookie". */
        String name();
        boolean isSignedIn();
        /** Full "Cookie:" header value, or null. */
        String cookieHeader();
        /** Raw SAPISID value (for SAPISIDHASH), or null. */
        String sapisid();
        /** Clear the in-memory session (storage is the caller's job). */
        void signOut();
    }

    /** The only session source in v1.0.36: the user's own cookies,
     * captured from his own sign-in in the TV WebView. */
    private static class CookieSession implements SessionSource {
        private final String cookieHeader;
        private final String sapisid;
        private final boolean signedIn;
        CookieSession(String cookieHeader, Map<String, String> cookies) {
            this.cookieHeader = cookieHeader;
            // yt-dlp falls back to __Secure-3PAPISID when SAPISID is
            // absent (yt-dlp issue #393); the hash input is identical.
            String s = cookies.get("SAPISID");
            if (s == null || s.isEmpty()) s = cookies.get("__Secure-3PAPISID");
            this.sapisid = s;
            // LOGIN_INFO is the logged-in marker (MeeTube §6); SID+SAPISID
            // alone is accepted as a weaker fallback.
            this.signedIn = cookies.containsKey("LOGIN_INFO")
                    || (cookies.containsKey("SID") && sapisid != null && !sapisid.isEmpty());
        }
        @Override public String name() { return "cookie"; }
        @Override public boolean isSignedIn() { return signedIn; }
        @Override public String cookieHeader() { return cookieHeader; }
        @Override public String sapisid() { return sapisid; }
        @Override public void signOut() { /* fields are final; the holder drops the reference */ }
    }

    private static volatile YouTubeAuth instance;
    private volatile Context appCtx;
    private volatile SessionSource session;
    private volatile boolean loaded = false;
    private volatile String accountName = "";

    // v1.0.36: Premium probe (instrument only — NEVER gates playback).
    // Set by YoutubeApi.resolveSignedIn() from the signed-in player
    // response: ads present? usable streams? Set once per video.
    private volatile boolean probePremiumLikely = false;
    private volatile String probeSummary = "";
    private volatile String probeVideoId = "";

    public static YouTubeAuth getInstance() { return getInstance(null); }
    public static synchronized YouTubeAuth getInstance(Context ctx) {
        if (instance == null) instance = new YouTubeAuth();
        if (ctx != null) instance.appCtx = ctx.getApplicationContext();
        if (instance.appCtx == null) instance.appCtx = StartupLog.appContext();
        if (instance.appCtx != null && !instance.loaded) instance.loadStored();
        return instance;
    }

    /** Swap the session source (future own-client device flow). Not used
     * in v1.0.36 — the cookie session is the only source. */
    public synchronized void setSessionSource(SessionSource s) { session = s; }
    public String sessionSourceName() {
        SessionSource s = session;
        return s == null ? "none" : s.name();
    }

    public boolean isSignedIn() {
        SessionSource s = session;
        return s != null && s.isSignedIn();
    }

    public String accountName() { return accountName == null ? "" : accountName; }
    public void setAccountName(String name) {
        if (name == null) name = "";
        name = name.trim();
        if (name.equals(accountName)) return;
        accountName = name;
        try {
            if (appCtx != null) appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ACCOUNT, name).apply();
        } catch (Exception ignored) { }
    }

    /** Attach Cookie + SAPISIDHASH + Origin headers to an InnerTube call.
     * No-op when signed out. Never logs cookie values. */
    public void applyAuthHeaders(java.net.HttpURLConnection c) {
        SessionSource s = session;
        if (s == null || !s.isSignedIn()) return;
        String cookies = s.cookieHeader();
        if (cookies == null || cookies.isEmpty()) return;
        try {
            c.setRequestProperty("Cookie", cookies);
            String hash = sapisidHashHeader(s.sapisid());
            if (hash != null) c.setRequestProperty("Authorization", hash);
            c.setRequestProperty("X-Origin", SAPISID_ORIGIN);
            c.setRequestProperty("X-Goog-AuthUser", "0");
            c.setRequestProperty("X-Youtube-Bootstrap-Logged-In", "true");
        } catch (Exception ignored) { }
    }

    /** "SAPISIDHASH <ts>_<sha1(ts + ' ' + sapisid + ' ' + origin)>"
     * (yt-dlp _generate_sapisidhash_header). Null when no SAPISID. */
    public static String sapisidHashHeader(String sapisid) {
        if (sapisid == null || sapisid.isEmpty()) return null;
        try {
            long ts = System.currentTimeMillis() / 1000;
            String input = ts + " " + sapisid + " " + SAPISID_ORIGIN;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b & 0xff));
            return "SAPISIDHASH " + ts + "_" + hex.toString();
        } catch (Exception e) { return null; }
    }

    /** Parse a CookieManager header ("a=b; c=d") into name->value.
     * Values are kept in memory only; never logged. */
    private static Map<String, String> parseCookies(String header) {
        Map<String, String> m = new HashMap<>();
        if (header == null) return m;
        for (String part : header.split(";")) {
            String p = part.trim();
            int i = p.indexOf('=');
            if (i > 0) m.put(p.substring(0, i).trim(), p.substring(i + 1).trim());
        }
        return m;
    }

    /** Cookie NAMES only (for logs) — never values. */
    private static String cookieNames(Map<String, String> cookies) {
        List<String> names = new ArrayList<>(cookies.keySet());
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append(", "); sb.append(n); }
        return sb.toString();
    }

    /**
     * Capture the session from the TV WebView's CookieManager after the
     * user signed in on Google's own page. Returns true when a signed-in
     * session was stored. Requires LOGIN_INFO (or SID+SAPISID) — a header
     * with only VISITOR_INFO1_LIVE / CONSENT is NOT a sign-in.
     */
    public synchronized boolean saveFromWebViewCookies(String cookieHeader) {
        Map<String, String> cookies = parseCookies(cookieHeader);
        CookieSession cand = new CookieSession(cookieHeader, cookies);
        if (!cand.isSignedIn()) {
            StartupLog.log("Dang nhap YouTube: chua thay LOGIN_INFO (cookie: "
                    + cookieNames(cookies) + ")");
            return false;
        }
        session = cand;
        if (!storeEncrypted(cookieHeader)) {
            // Keystore unavailable (API < 23 or broken keystore): the
            // session still works for this process lifetime, but it will
            // NOT survive a restart. Never store cookies in plaintext.
            StartupLog.log("Dang nhap YouTube OK (chi trong phien nay — "
                    + "khong luu duoc keystore; cookie: " + cookieNames(cookies) + ")");
            return true;
        }
        StartupLog.log("Dang nhap YouTube OK (da luu ma hoa; cookie: "
                + cookieNames(cookies) + ")");
        return true;
    }

    /** Sign out: drop the in-memory session, delete the encrypted blob
     * + account name, and clear the youtube.com cookies from the
     * WebView's CookieManager (main thread — CookieManager requires it).
     * After this the app behaves EXACTLY like v1.0.35 (anonymous ladder). */
    public synchronized void signOut() {
        session = null;
        accountName = "";
        probePremiumLikely = false;
        probeSummary = "";
        probeVideoId = "";
        try {
            if (appCtx != null) appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_BLOB).remove(KEY_ACCOUNT).apply();
        } catch (Exception ignored) { }
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.removeSessionCookies(null);
                        cm.flush();
                    } catch (Exception ignored) { }
                }
            });
        } catch (Exception ignored) { }
        StartupLog.log("Dang xuat YouTube: da xoa phien + cookie");
    }

    // ---------- Premium probe (instrument only) ----------

    /** Record the probe outcome for one video (from YoutubeApi). */
    public void setProbe(String videoId, boolean likely, String summary) {
        probeVideoId = videoId == null ? "" : videoId;
        probePremiumLikely = likely;
        probeSummary = summary == null ? "" : summary;
    }
    public boolean probePremiumLikely() { return probePremiumLikely; }
    public String probeSummary() { return probeSummary; }
    public String probeVideoId() { return probeVideoId; }

    // ---------- manual "Tôi có Premium" toggle (persisted) ----------

    public boolean isPremiumManual() {
        try {
            if (appCtx == null) return false;
            return appCtx.getSharedPreferences("karaoke_prefs", Context.MODE_PRIVATE)
                    .getBoolean("premium_manual", false);
        } catch (Exception e) { return false; }
    }
    public void setPremiumManual(boolean on) {
        try {
            if (appCtx != null) appCtx.getSharedPreferences("karaoke_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("premium_manual", on).apply();
        } catch (Exception ignored) { }
        StartupLog.log("Premium (tay): " + (on ? "BAT — anh bao co Premium" : "TAT"));
    }

    /** Effective Premium indicator: manual toggle wins, else the probe. */
    public boolean isPremiumLikely() {
        return isPremiumManual() || probePremiumLikely;
    }
    /** "manual" | "probe" | "none" — where the indicator came from. */
    public String premiumSource() {
        if (isPremiumManual()) return "manual";
        if (probePremiumLikely) return "probe";
        return "none";
    }

    // ---------- encrypted storage (AndroidKeyStore AES/GCM) ----------

    private synchronized void loadStored() {
        loaded = true;
        if (appCtx == null) return;
        try {
            SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            accountName = p.getString(KEY_ACCOUNT, "");
            String blob = p.getString(KEY_BLOB, null);
            if (blob == null || blob.isEmpty()) return;
            String cookies = decrypt(blob);
            if (cookies == null || cookies.isEmpty()) {
                StartupLog.log("YouTubeAuth: khong giai ma duoc phien da luu — can dang nhap lai");
                return;
            }
            Map<String, String> m = parseCookies(cookies);
            CookieSession cand = new CookieSession(cookies, m);
            if (cand.isSignedIn()) {
                session = cand;
                StartupLog.log("YouTubeAuth: khoi phuc phien da luu (cookie: " + cookieNames(m) + ")");
            } else {
                StartupLog.log("YouTubeAuth: phien da luu het han — can dang nhap lai");
            }
        } catch (Exception e) {
            StartupLog.log("YouTubeAuth load LOI: " + e);
        }
    }

    /** Returns false when the blob could NOT be stored encrypted
     * (caller keeps the session in memory only). */
    private boolean storeEncrypted(String cookies) {
        if (appCtx == null) return false;
        if (Build.VERSION.SDK_INT < 23) {
            StartupLog.log("YouTubeAuth: API < 23 — khong co Keystore, chi giu phien trong RAM");
            return false;
        }
        try {
            String blob = encrypt(cookies);
            if (blob == null) return false;
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_BLOB, blob).apply();
            return true;
        } catch (Exception e) {
            StartupLog.log("YouTubeAuth luu LOI: " + e);
            return false;
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key key = ks.getKey(KEYSTORE_ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        KeyGenerator kg = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** Base64( IV(12) || ciphertext+tag ). */
    private String encrypt(String plain) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            StartupLog.log("YouTubeAuth ma hoa LOI: " + e);
            return null;
        }
    }

    private String decrypt(String blob) {
        try {
            byte[] raw = Base64.decode(blob, Base64.DEFAULT);
            if (raw.length <= 12) return null;
            byte[] iv = new byte[12];
            byte[] ct = new byte[raw.length - 12];
            System.arraycopy(raw, 0, iv, 0, 12);
            System.arraycopy(raw, 12, ct, 0, ct.length);
            SecretKey key = getOrCreateKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
