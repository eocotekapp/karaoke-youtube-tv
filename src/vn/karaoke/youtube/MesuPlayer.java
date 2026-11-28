package vn.karaoke.youtube;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ExoPlayer 2.19.1 wrapper (MesuPlayer equivalent). Clean rewrite.
 * No WakeLock — keep-alive is the foreground PlayerService.
 * State codes mirror the original stage contract:
 *   -1 = idle/error, 0 = ended, 1 = playing, 2 = paused, 3 = buffering.
 */
public class MesuPlayer {
    private ExoPlayer player;
    private final Context ctx;
    private Surface surface;
    private String currentId = "";
    private String currentTitle = "";
    private volatile int lastState = -1;
    private volatile String lastError = "";
    /** v1.0.29: ExoPlayer keeps no readable volume — remember the last
     * setVolume() so /api/state can report volume 0-100 (the Gathin
     * panel's slider reads s.volume on every refresh). */
    private volatile float lastVolume = 1.0f;

    /** v1.0.32: position / duration / video-size, polled on the MAIN
     * thread and cached here. WHY: getState() is called from
     * RemoteServer's HTTP pool thread, and ExoPlayer 2.19.1 verifies
     * the application thread in getCurrentPosition(), getDuration()
     * and getVideoSize() — proven by javap on the exact
     * exoplayer-core-2.19.1.aar in libs/ (all three call
     * verifyApplicationThread first). Off-thread they throw
     * IllegalStateException, which getState()'s inner try/catches
     * swallowed — so /api/state always reported pos=0, dur=0, w=0,
     * h=0 while st=1 (listener-maintained) was correct, and the
     * panel's progress bar sat at 0:00 forever. Same thread check
     * that broke pause()/stop() from the pool thread in v1.0.26.
     * A 500 ms main-thread poller keeps these fields fresh (at most
     * 500 ms stale — the panel polls every 2.5 s); getState() only
     * reads the cache and never touches ExoPlayer off-thread. */
    private volatile long lastPosMs = 0;
    private volatile long lastDurMs = 0;
    private volatile int lastVideoW = 0;
    private volatile int lastVideoH = 0;
    private android.os.Handler posHandler;
    private final Runnable posPoll = new Runnable() {
        @Override public void run() {
            try {
                if (player != null) {
                    try { lastPosMs = player.getCurrentPosition(); } catch (Exception ignored) { }
                    try {
                        long d = player.getDuration();
                        lastDurMs = (d == C.TIME_UNSET) ? 0 : d;
                    } catch (Exception ignored) { }
                    try {
                        if (player.getVideoSize() != null) {
                            lastVideoW = player.getVideoSize().width;
                            lastVideoH = player.getVideoSize().height;
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            if (player != null && posHandler != null) posHandler.postDelayed(this, 500);
        }
    };

    /** v1.0.30: HLS loads — master manifest, variant playlists, segments
     * — must ALL go through the local /stream proxy (the proven path:
     * RemoteServer.proxyStream forwards Range and streams bytes).
     * ExoPlayer's HlsMediaSource fetches every one of those through its
     * DataSource.Factory, so this small DefaultHttpDataSource subclass
     * rewrites ANY URI it is asked to open to the proxy URL. Manifest
     * contents are NOT rewritten. getUri() still returns the ORIGINAL
     * URI so ExoPlayer resolves relative URIs against the real playlist
     * location (YouTube's variant playlists happen to use absolute
     * segment URIs, but this keeps the player's view of the world
     * identical to a direct load). URIs already pointing at the proxy
     * pass through unchanged — no double proxying. Used by the HLS
     * branch ONLY; progressive/DASH keep the plain factory, untouched.
     * Verified live 2026-11-28: the Invidious master manifest for
     * jkVlEvuGjg8 has one variant (1920x1080) with absolute
     * youtube.com variant URIs, and its variant playlist lists 902
     * absolute googlevideo segment URIs — all three load kinds hit
     * this factory. v1.0.31: used ONLY by the one-shot direct->proxy
     * HLS retry (retryHlsViaProxy); the first HLS attempt is direct
     * via DIRECT_SAFARI_FACTORY. */
    private static final String PROXY_PREFIX = "http://127.0.0.1:8792/stream?u=";
    private static class ProxyHttpDataSource extends DefaultHttpDataSource {
        private Uri originalUri;
        ProxyHttpDataSource() {
            super(YoutubeApi.UA, 15000, 30000, true, new HttpDataSource.RequestProperties());
        }
        @Override
        public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
            originalUri = dataSpec.uri;
            String s = dataSpec.uri.toString();
            if (!s.startsWith(PROXY_PREFIX)) {
                try {
                    dataSpec = dataSpec.withUri(Uri.parse(PROXY_PREFIX +
                            java.net.URLEncoder.encode(s, "UTF-8")));
                } catch (Exception ignored) { }
            }
            return super.open(dataSpec);
        }
        @Override
        public Uri getUri() {
            return originalUri != null ? originalUri : super.getUri();
        }
    }
    private static final DataSource.Factory PROXY_HTTP_FACTORY = new DataSource.Factory() {
        @Override public DataSource createDataSource() { return new ProxyHttpDataSource(); }
    };

    /** v1.0.31: direct HLS loads use the ORIGINAL Gathin factory —
     * DefaultHttpDataSource with the Safari UA (the original
     * MesuPlayer$1.run built its HlsMediaSource exactly this way:
     * Safari UA, cross-protocol redirects on, connect 15s, read 30s)
     * and NO proxy rewrite. The proxy (PROXY_HTTP_FACTORY above)
     * is only the second attempt — see onPlayerError +
     * retryHlsViaProxy. */
    private static final HttpDataSource.Factory DIRECT_SAFARI_FACTORY = new DefaultHttpDataSource.Factory()
            .setUserAgent(YoutubeApi.VISIONOS_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000);

    /** v1.0.31: direct-then-proxy HLS retry state (per track).
     * hlsDirectUrl = the raw manifest URL of the current direct-HLS
     * attempt (null when the current track is not direct-HLS);
     * hlsProxyRetried = the one proxy retry was already used;
     * hlsPlayedOnce = the track reached READY/playing at least once
     * (a later error is a playback problem, not a source-load
     * problem, so it must NOT restart the track via proxy). All
     * three are reset in play() for every new track; hlsDirectUrl is
     * also cleared in stop(). Guarded by the player lock (all
     * writes/reads happen on the main thread). */
    private volatile String hlsDirectUrl = null;
    private volatile boolean hlsProxyRetried = false;
    private volatile boolean hlsPlayedOnce = false;

    /** v1.0.37: the one 403 re-resolve (see onPlayerError) was already
     * used for this track. Per TRACK, not per candidate — reset in
     * play() (new track) and stop(), never in buildSourceForCandidate
     * (a fresh candidate list from the retry must not re-trigger it). */
    private volatile boolean hls403Retried = false;

    /** v1.0.35: runtime candidate fallback (the proven-live bug:
     * prepareFromCandidates played the FIRST usable candidate and
     * broke — a "Source error" on it killed the track even though
     * the resolve returned more candidates, and the ~30 s stall
     * watchdog then skipped the track). The resolved list + the
     * index of the candidate currently attempted are kept for the
     * track so onPlayerError can advance to the next one instead
     * of giving up. Both reset in play() for every new track;
     * trackCandidates is also cleared in stop() so a stale posted
     * advance can never re-prepare after a stop. */
    private volatile JSONArray trackCandidates = null;
    private volatile int trackCandIndex = 0;

    public MesuPlayer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** v1.0.34: one-line cause detail for a PlaybackException — the
     * ExoPlaybackException type (SOURCE/RENDERER/UNEXPECTED/REMOTE),
     * renderer index + name, error-code name and the cause chain (up
     * to 4 levels, class + message each, each message capped at 200
     * chars). Appended to the "Phat LOI" line so a PLAY-ERR upload
     * names the real failure: earlier uploads only carried the top
     * message ("Source error" / "Unexpected runtime error") with no
     * usable cause, which is why long-video failures could not be
     * diagnosed. NOTE: 2.19.1 has NO TYPE_TIMEOUT (timeouts surface
     * as ERROR_CODE_TIMEOUT / ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
     * in the error-code name); the switch defaults to the raw int
     * for any value it does not know. */
    private static String errDetail(PlaybackException e) {
        StringBuilder b = new StringBuilder();
        if (e instanceof ExoPlaybackException) {
            ExoPlaybackException x = (ExoPlaybackException) e;
            b.append(" | type=");
            switch (x.type) {
                case ExoPlaybackException.TYPE_SOURCE: b.append("SOURCE"); break;
                case ExoPlaybackException.TYPE_RENDERER: b.append("RENDERER"); break;
                case ExoPlaybackException.TYPE_UNEXPECTED: b.append("UNEXPECTED"); break;
                case ExoPlaybackException.TYPE_REMOTE: b.append("REMOTE"); break;
                default: b.append(x.type); break;
            }
            b.append(" renderer=").append(x.rendererIndex);
            if (x.rendererName != null) b.append(" (").append(x.rendererName).append(")");
        }
        try { b.append(" | code=").append(e.getErrorCodeName()); } catch (Exception ignored) { }
        Throwable c = e.getCause();
        int n = 0;
        b.append(" | cause:");
        if (c == null) b.append(" -");
        while (c != null && n < 4) {
            String m = c.getMessage();
            if (m == null) m = "";
            if (m.length() > 200) m = m.substring(0, 200) + "…";
            b.append(" [").append(c.getClass().getSimpleName()).append(": ").append(m).append("]");
            c = c.getCause();
            n++;
        }
        return b.toString();
    }

    public synchronized void ensurePlayer() {
        if (player != null) return;
        HttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(YoutubeApi.UA)
                .setAllowCrossProtocolRedirects(true);
        // v1.0.17: 1080p60 by user demand — the FPT Play 650 did die once on
        // a 1080p60 segment (log 2026-11-28 07:06) but the user explicitly
        // wants 60fps, so NO frame-rate cap. 12 Mbps keeps 1080p60
        // selectable (YouTube 1080p60 variants run ~8-12 Mbps). Max 1080p.
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(ctx);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setMaxVideoBitrate(12000000));
        player = new ExoPlayer.Builder(ctx)
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();
        player.setVolume(1.0f);
        if (surface != null) player.setVideoSurface(surface);
        // v1.0.32: start the main-thread position poller (see lastPosMs).
        if (posHandler == null) posHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        posHandler.removeCallbacks(posPoll);
        posHandler.post(posPoll);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) lastState = 3;
                else if (state == Player.STATE_ENDED) lastState = 0;
                else if (state == Player.STATE_READY) { lastState = player.getPlayWhenReady() ? 1 : 2; hlsPlayedOnce = true; }
                else lastState = -1;
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) hlsPlayedOnce = true;
                lastState = isPlaying ? 1 : (player != null && player.getPlaybackState() == Player.STATE_READY ? 2 : lastState);
            }
            @Override public void onVideoSizeChanged(VideoSize videoSize) {
                lastVideoW = videoSize.width; lastVideoH = videoSize.height;
                StartupLog.log("Video: " + videoSize.width + "x" + videoSize.height);
            }
            @Override public void onPlayerError(PlaybackException error) {
                // v1.0.37 (403): a pre-first-frame HTTP 403 (manifest URL
                // stale/expired, or the visitor session flagged — proven
                // live 2026-11-28 ~20:11: direct HLS 403 AND the proxy
                // retry 403 on every track after ~2 h of use). Retrying
                // the same URL via the proxy is useless (it 403s too) —
                // mint FRESH URLs instead: re-resolve the track once with
                // a cleared session and replay from the fresh candidate
                // list. One attempt only (hls403Retried); a second 403
                // falls through to the proxy/candidate paths below.
                // Mid-play errors (hlsPlayedOnce) never take this path.
                if (!hlsPlayedOnce && !hls403Retried && isHttp403(error)) {
                    hls403Retried = true;
                    final String retryId = currentId;
                    StartupLog.log("Phat " + currentId + ": 403, lay link moi");
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { retryResolveAfter403(retryId); }
                    });
                    return;
                }
                // v1.0.31 (a): direct-then-proxy HLS retry (mirrors the
                // original stage's [u, proxyUrl(u)] candidates). If the
                // current track is a direct-HLS attempt that errored
                // BEFORE ever reaching READY/playing, the load itself
                // failed — retry ONCE via the /stream proxy. The retry
                // is posted to the main looper and re-checks that the
                // track is still current (retryHlsViaProxy compares u
                // against hlsDirectUrl, which play() clears for a new
                // track). No PLAY-ERR upload yet — the track is not
                // dead until the proxy attempt also fails.
                // v1.0.35 (b): if the proxy retry was already used (or
                // the failed candidate was not direct-HLS) and more
                // candidates remain, advance to the next one instead
                // of giving up — the resolve usually returns
                // [hls (visionos), hls (invidious), progressive
                // itag18], so a dead HLS manifest falls through to
                // 360p instead of killing the track. The advance is
                // posted to the main looper with the same staleness
                // guard (advanceToNextCandidate re-checks id + list).
                // (c) only when the list is exhausted, or the error
                // came mid-play (hlsPlayedOnce — a playback problem,
                // not a source-load problem, so it must NOT restart
                // the track), does the normal PLAY-ERR path run.
                if (hlsDirectUrl != null && !hlsProxyRetried && !hlsPlayedOnce) {
                    hlsProxyRetried = true;
                    final String u = hlsDirectUrl;
                    StartupLog.log("Phat " + currentId + ": hls truc tiep LOI, thu qua proxy: " + error.getMessage() + errDetail(error));
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { retryHlsViaProxy(u); }
                    });
                    return;
                }
                if (!hlsPlayedOnce && trackCandidates != null && trackCandIndex + 1 < trackCandidates.length()) {
                    final String errId = currentId;
                    final JSONArray savedCands = trackCandidates;
                    final int failedIndex = trackCandIndex;
                    final String errLine = (error.getMessage() == null ? "playback error" : error.getMessage()) + errDetail(error);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { advanceToNextCandidate(errId, savedCands, failedIndex, errLine); }
                    });
                    return;
                }
                lastState = -1;
                lastError = error.getMessage() == null ? "playback error" : error.getMessage();
                StartupLog.log("Phat LOI (" + currentId + " '" + currentTitle + "'): " + error + errDetail(error));
                LogUploader.send("PLAY-ERR");
            }
        });
    }

    public synchronized void ensureSurface(Surface s) {
        surface = s;
        if (player != null) player.setVideoSurface(s);
    }

    public synchronized void play(String videoId, String title) {
        ensurePlayer();
        currentId = videoId;
        currentTitle = title == null ? "" : title;
        lastState = 3;
        lastError = "";
        lastPosMs = 0; lastDurMs = 0; // v1.0.32: fresh track — no stale position
        // v1.0.31: fresh track — reset the direct-then-proxy HLS state.
        hlsDirectUrl = null;
        hlsProxyRetried = false;
        hlsPlayedOnce = false;
        // v1.0.37: fresh track — a later 403 still gets its one re-resolve.
        hls403Retried = false;
        // v1.0.35: fresh track — no candidate list until resolve returns.
        trackCandidates = null;
        trackCandIndex = 0;
        StartupLog.log("Phat: " + videoId + " '" + currentTitle + "'");
        new Thread(new Runnable() {
            @Override public void run() {
            try {
                YoutubeApi api = new YoutubeApi();
                final JSONObject r = api.resolve(videoId);
                JSONArray cands = r.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) throw new Exception("no stream");
                final JSONArray fcands = cands;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override public void run() { prepareFromCandidates(r, fcands); }
                });
            } catch (Exception e) {
                lastState = -1;
                lastError = e.getMessage() == null ? "resolve failed" : e.getMessage();
                StartupLog.log("Phat " + videoId + " LOI resolve: " + e);
                LogUploader.send("PLAY-ERR");
            }
            }
        }).start();
    }

    /** v1.0.37: play a GIVEN candidate list for a track, without
     * resolving (the manual "Phát bản 360p" fix button resolves
     * first and hands over only the itag 18 candidate). Same state
     * reset + logging as play(); the prepare is posted to the main
     * looper exactly like play()'s resolver thread does. The
     * hls403Retried flag is reset here too — this is a fresh play
     * attempt for the track, so a later 403 still gets its one
     * re-resolve. */
    public synchronized void playCandidates(String videoId, String title, final JSONArray cands) {
        ensurePlayer();
        currentId = videoId;
        currentTitle = title == null ? "" : title;
        lastState = 3;
        lastError = "";
        lastPosMs = 0; lastDurMs = 0;
        hlsDirectUrl = null;
        hlsProxyRetried = false;
        hlsPlayedOnce = false;
        hls403Retried = false;
        trackCandidates = null;
        trackCandIndex = 0;
        StartupLog.log("Phat: " + videoId + " '" + currentTitle + "' (chi phat tu danh sach co san)");
        final JSONObject r = new JSONObject();
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() { prepareFromCandidates(r, cands); }
        });
    }

    /** v1.0.31: the proxy half of the direct-then-proxy HLS retry.
     * Runs on the main thread (posted from onPlayerError). Drops the
     * retry when the track is no longer current — play() clears
     * hlsDirectUrl for a new track, so a stale retry can never
     * re-prepare an old URL over a new track. At most one proxy
     * attempt per track (hlsProxyRetried is set before posting). If
     * the proxy attempt also fails, onPlayerError takes the normal
     * PLAY-ERR path (hlsProxyRetried is already true). */
    private synchronized void retryHlsViaProxy(String u) {
        if (player == null || u == null || !u.equals(hlsDirectUrl)) return;
        // v1.0.35: a new attempt restarts the ~30 s stall clock.
        PlayerService svc = PlayerService.getInstance();
        if (svc != null) svc.resetStallPolls();
        try {
            MediaItem item = MediaItem.fromUri(Uri.parse(u));
            MediaSource source = new HlsMediaSource.Factory(PROXY_HTTP_FACTORY).createMediaSource(item);
            player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);
            StartupLog.log("Phat " + currentId + ": hls qua proxy, dang phat");
        } catch (Exception e) {
            lastState = -1;
            lastError = e.getMessage() == null ? "prepare failed" : e.getMessage();
            StartupLog.log("Phat " + currentId + " LOI prepare (proxy): " + e);
            LogUploader.send("PLAY-ERR");
        }
    }

    /** v1.0.37: is this error an HTTP 403 on the media load? The
     * observed signature (PLAY-ERR uploads 2026-11-28 ~20:11) is
     * type=SOURCE, code ERROR_CODE_IO_BAD_HTTP_STATUS, cause
     * InvalidResponseCodeException "Response code: 403". Both halves
     * are checked — a 403 buried in another error class must not
     * trigger a re-resolve. */
    private static boolean isHttp403(PlaybackException e) {
        if (e == null) return false;
        try {
            if (!"ERROR_CODE_IO_BAD_HTTP_STATUS".equals(e.getErrorCodeName())) return false;
        } catch (Exception ignored) { return false; }
        Throwable c = e.getCause();
        int n = 0;
        while (c != null && n < 4) {
            String m = c.getMessage();
            if (m != null && m.contains("403")) return true;
            c = c.getCause();
            n++;
        }
        return false;
    }

    /** v1.0.37: the 403 re-resolve (posted from onPlayerError, runs
     * on the main thread). Re-resolves the SAME track with a cleared
     * session (fresh visitorData + fresh manifest URLs — the old URLs
     * are what 403'd) and prepares from the fresh candidate list.
     * The hls403Retried flag is NOT reset here (play() was not
     * called), so a second 403 on the fresh list cannot loop back
     * here — it falls through to the proxy/candidate paths. Drops
     * the retry when the track is no longer current (the user moved
     * on while the resolve was in flight). On resolve failure the
     * normal PLAY-ERR path runs — the player is left idle, never
     * stuck mid-retry. */
    private synchronized void retryResolveAfter403(String videoId) {
        if (player == null || videoId == null || !videoId.equals(currentId)) return;
        PlayerService svc = PlayerService.getInstance();
        if (svc != null) svc.resetStallPolls();
        hlsDirectUrl = null;
        hlsProxyRetried = false;
        hlsPlayedOnce = false;
        trackCandidates = null;
        trackCandIndex = 0;
        lastState = 3;
        lastError = "";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    YoutubeApi api = new YoutubeApi();
                    api.clearSession(videoId);
                    final JSONObject r = api.resolve(videoId);
                    JSONArray cands = r.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) throw new Exception("no stream");
                    final JSONArray fcands = cands;
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (!videoId.equals(currentId)) return;
                            prepareFromCandidates(r, fcands);
                        }
                    });
                } catch (Exception e) {
                    lastState = -1;
                    lastError = e.getMessage() == null ? "resolve failed" : e.getMessage();
                    StartupLog.log("Phat " + videoId + " LOI resolve (403 retry): " + e);
                    LogUploader.send("PLAY-ERR");
                }
            }
        }).start();
    }

    /** v1.0.35: build the MediaSource for ONE candidate (index i of
     * the stored trackCandidates). This is the old
     * prepareFromCandidates loop body, extracted verbatim per kind:
     * HLS kind (or .m3u8 URL) -> HlsMediaSource DIRECT first
     * (Safari UA, no proxy) + sets hlsDirectUrl/hlsProxyRetried/
     * hlsPlayedOnce for the attempt; otherwise -> /stream proxy URL
     * for manifest/.m3u8/googlevideo URLs, Dash or Progressive.
     * Returns null for an unusable entry (missing/empty URL) and
     * touches no HLS state in that case. Called on the main thread. */
    private MediaSource buildSourceForCandidate(int i) throws Exception {
        if (trackCandidates == null || i < 0 || i >= trackCandidates.length()) return null;
        JSONObject c = trackCandidates.optJSONObject(i);
        if (c == null) return null;
        String url = c.optString("url");
        String kind = c.optString("kind");
        if (url == null || url.isEmpty()) return null;
        // Fresh attempt: no direct-HLS proxy retry used yet, no frame
        // played yet. The HLS branch below sets hlsDirectUrl; a
        // non-HLS candidate must clear it so the old manifest URL
        // can never trigger a proxy retry for the wrong source.
        hlsDirectUrl = null;
        hlsProxyRetried = false;
        hlsPlayedOnce = false;
        if ("hls".equals(kind) || url.contains(".m3u8")) {
            // v1.0.31: HLS plays DIRECT first (original Gathin
            // behaviour — Safari UA, no proxy). The item URI
            // stays RAW: the proxy factory rewrites it at open
            // time only if the one-shot retry fires (see
            // onPlayerError + retryHlsViaProxy). If the direct
            // load errors before first frame, the retry rebuilds
            // the same source with PROXY_HTTP_FACTORY.
            hlsDirectUrl = url;
            MediaItem item = MediaItem.fromUri(Uri.parse(url));
            MediaSource source = new HlsMediaSource.Factory(DIRECT_SAFARI_FACTORY).createMediaSource(item);
            StartupLog.log("Phat " + currentId + ": hls truc tiep");
            return source;
        }
        // Route through the local /stream proxy when the URL is a manifest
        // or when direct playback is blocked (CORS/403 on googlevideo).
        HttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(YoutubeApi.UA)
                .setAllowCrossProtocolRedirects(true);
        String playUrl = url;
        if (url.contains("manifest") || url.contains(".m3u8") || url.contains("googlevideo.com")) {
            playUrl = "http://127.0.0.1:8792/stream?u=" +
                    java.net.URLEncoder.encode(url, "UTF-8");
        }
        Uri uri = Uri.parse(playUrl);
        MediaItem item = MediaItem.fromUri(uri);
        if (url.contains(".mpd")) {
            return new DashMediaSource.Factory(http).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(http).createMediaSource(item);
    }

    /** v1.0.35: runtime candidate fallback. Runs on the main thread
     * (posted from onPlayerError). Drops a stale advance: the saved
     * list must still be the current track's list AND the id must
     * match — play() for a new track replaces the list, stop()
     * clears it, release() nulls the player, so a late runnable
     * can never re-prepare an old source over a new track. Scans
     * forward from failedIndex+1 for the next usable candidate
     * (skips empty-URL entries exactly like the first prepare);
     * each new attempt resets the stall watchdog clock. Only when
     * the list is exhausted does it take the PLAY-ERR path. */
    private synchronized void advanceToNextCandidate(String errId, JSONArray savedCands, int failedIndex, String errLine) {
        if (player == null || savedCands == null || savedCands != trackCandidates
                || errId == null || !errId.equals(currentId)) return;
        try {
            MediaSource source = null;
            int nextIndex = -1;
            for (int i = failedIndex + 1; i < savedCands.length(); i++) {
                source = buildSourceForCandidate(i);
                if (source != null) { nextIndex = i; break; }
            }
            if (source == null) {
                lastState = -1;
                lastError = "het ung vien";
                StartupLog.log("Phat LOI (" + currentId + " '" + currentTitle + "'): het ung vien, da thu " + (failedIndex + 1) + " ung vien");
                LogUploader.send("PLAY-ERR");
                return;
            }
            trackCandIndex = nextIndex;
            // v1.0.35: a new attempt restarts the ~30 s stall clock.
            PlayerService svc = PlayerService.getInstance();
            if (svc != null) svc.resetStallPolls();
            JSONObject c = savedCands.optJSONObject(nextIndex);
            String kind = c == null ? "?" : c.optString("kind", "?");
            StartupLog.log("Phat " + currentId + ": ung vien " + failedIndex + " LOI (" + errLine + "), thu ung vien " + nextIndex + " (" + kind + ")");
            player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);
            StartupLog.log("Phat " + currentId + ": da chuan bi xong, dang phat");
        } catch (Exception e) {
            lastState = -1;
            lastError = e.getMessage() == null ? "prepare failed" : e.getMessage();
            StartupLog.log("Phat " + currentId + " LOI prepare (ung vien " + (failedIndex + 1) + "): " + e);
            LogUploader.send("PLAY-ERR");
        }
    }

    /** v1.0.37: manual "Đổi nguồn phát" (fix button) — advance to
     * the next candidate of the CURRENT track, reusing the v1.0.35
     * advanceToNextCandidate path (same staleness guard, same stall
     * reset, same PLAY-ERR when the list is exhausted). Returns
     * false at once when there is no next candidate (the panel then
     * toasts "Hết nguồn khác để thử" and nothing changes). The
     * advance itself is posted to the main looper; calling this from
     * a pool thread is safe. */
    public boolean nextCandidate() {
        if (trackCandidates == null || trackCandIndex + 1 >= trackCandidates.length()) return false;
        final String errId = currentId;
        final JSONArray savedCands = trackCandidates;
        final int failedIndex = trackCandIndex;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { advanceToNextCandidate(errId, savedCands, failedIndex, "doi nguon phat (thu cong)"); }
        });
        return true;
    }

    private synchronized void prepareFromCandidates(JSONObject r, JSONArray cands) {
        if (player == null) return;
        // v1.0.31: a new prepare starts with no direct-HLS attempt in
        // flight; the HLS branch in buildSourceForCandidate sets
        // hlsDirectUrl when it runs.
        hlsDirectUrl = null;
        // v1.0.35: keep the list for the runtime fallback.
        trackCandidates = cands;
        trackCandIndex = 0;
        try {
            MediaSource source = null;
            for (int i = 0; i < cands.length(); i++) {
                source = buildSourceForCandidate(i);
                if (source != null) { trackCandIndex = i; break; }
            }
            if (source == null) { lastState = -1; lastError = "no usable candidate"; StartupLog.log("Phat " + currentId + " LOI: no usable candidate"); LogUploader.send("PLAY-ERR"); return; }
            player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);
            StartupLog.log("Phat " + currentId + ": da chuan bi xong, dang phat");
        } catch (Exception e) {
            lastState = -1;
            lastError = e.getMessage() == null ? "prepare failed" : e.getMessage();
            StartupLog.log("Phat " + currentId + " LOI prepare: " + e);
            LogUploader.send("PLAY-ERR");
        }
    }

    public synchronized void pause() { if (player != null) player.setPlayWhenReady(false); }
    public synchronized void resume() { if (player != null) player.setPlayWhenReady(true); }
    public synchronized void stop() {
        if (player != null) { player.stop(); player.clearMediaItems(); }
        hlsDirectUrl = null; // v1.0.31: a stopped track has no retry pending
        hls403Retried = false; // v1.0.37: a stopped track has no 403 retry pending
        trackCandidates = null; // v1.0.35: a stopped track has no fallback pending
        lastState = -1;
    }
    public synchronized void setVolume(float v) {
        lastVolume = v < 0f ? 0f : (v > 1f ? 1f : v);
        if (player != null) player.setVolume(lastVolume);
    }
    /** Volume 0-100 for /api/state (the Gathin panel slider). */
    public int getVolumePercent() { return Math.round(lastVolume * 100f); }
    public synchronized void seekTo(long ms) { if (player != null) player.seekTo(ms); }
    public synchronized void release() {
        if (posHandler != null) posHandler.removeCallbacks(posPoll); // v1.0.32
        if (player != null) { player.release(); player = null; }
    }

    /** "st,pos,dur,w,h,f" — same shape the stage JS expects from PX.getState().
     * v1.0.15: NEVER throws, from ANY thread. ExoPlayer 2.19.1 throws
     * IllegalStateException from getPlaybackState()/isPlaying() when called
     * off the application thread — and RemoteServer answers /api/state from
     * a pool thread, so the live calls killed the whole state reply ({}).
     * The listener-maintained lastState is the state source now.
     * v1.0.32: position/duration/video-size come from the main-thread
     * poller cache (lastPosMs/lastDurMs/lastVideoW/lastVideoH) for the
     * same reason — the live getters also verify the application
     * thread, so calling them here always threw and left pos/dur/w/h
     * at 0 (panel progress stuck at 0:00). This method now never
     * touches ExoPlayer at all. */
    public synchronized String getState() {
        try {
            if (player == null) return lastState + ",0,0,0,0,";
            int st = lastState;
            String f = (currentId == null ? "" : currentId) + "|" + (currentTitle == null ? "" : currentTitle);
            return st + "," + lastPosMs + "," + lastDurMs + "," + lastVideoW + "," + lastVideoH + "," + f;
        } catch (Throwable t) {
            return lastState + ",0,0,0,0,";
        }
    }

    public String getCurrentId() { return currentId; }
    public String getLastError() { return lastError; }
}
