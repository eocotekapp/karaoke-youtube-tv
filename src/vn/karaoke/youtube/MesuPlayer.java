package vn.karaoke.youtube;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
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

    public MesuPlayer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public synchronized void ensurePlayer() {
        if (player != null) return;
        HttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(YoutubeApi.UA)
                .setAllowCrossProtocolRedirects(true);
        // v1.0.14: cap video at 1280x720 / 4.5 Mbps exactly like the original
        // Gathin TrackSelector — the FPT Play 650 decoder advertises 1080p60
        // support but dies on a 1080p60 segment (log 2026-11-28 07:06).
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(ctx);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setMaxVideoBitrate(4500000));
        player = new ExoPlayer.Builder(ctx)
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();
        player.setVolume(1.0f);
        if (surface != null) player.setVideoSurface(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) lastState = 3;
                else if (state == Player.STATE_ENDED) lastState = 0;
                else if (state == Player.STATE_READY) lastState = player.getPlayWhenReady() ? 1 : 2;
                else lastState = -1;
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                lastState = isPlaying ? 1 : (player != null && player.getPlaybackState() == Player.STATE_READY ? 2 : lastState);
            }
            @Override public void onVideoSizeChanged(VideoSize videoSize) {
                StartupLog.log("Video: " + videoSize.width + "x" + videoSize.height);
            }
            @Override public void onPlayerError(PlaybackException error) {
                lastState = -1;
                lastError = error.getMessage() == null ? "playback error" : error.getMessage();
                StartupLog.log("Phat LOI (" + currentId + " '" + currentTitle + "'): " + error);
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

    private synchronized void prepareFromCandidates(JSONObject r, JSONArray cands) {
        if (player == null) return;
        try {
            HttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(YoutubeApi.UA)
                    .setAllowCrossProtocolRedirects(true);
            MediaSource source = null;
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i);
                if (c == null) continue;
                String url = c.optString("url");
                String kind = c.optString("kind");
                if (url == null || url.isEmpty()) continue;
                // Route through the local /stream proxy when the URL is a manifest
                // or when direct playback is blocked (CORS/403 on googlevideo).
                String playUrl = url;
                if (url.contains("manifest") || url.contains(".m3u8") || url.contains("googlevideo.com")) {
                    playUrl = "http://127.0.0.1:8792/stream?u=" +
                            java.net.URLEncoder.encode(url, "UTF-8");
                }
                Uri uri = Uri.parse(playUrl);
                MediaItem item = MediaItem.fromUri(uri);
                if ("hls".equals(kind) || url.contains(".m3u8")) {
                    source = new HlsMediaSource.Factory(http).createMediaSource(item);
                } else if (url.contains(".mpd")) {
                    source = new DashMediaSource.Factory(http).createMediaSource(item);
                } else {
                    source = new ProgressiveMediaSource.Factory(http).createMediaSource(item);
                }
                break; // first usable candidate wins; stage watchdog advances on stall
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
        lastState = -1;
    }
    public synchronized void setVolume(float v) { if (player != null) player.setVolume(v); }
    public synchronized void seekTo(long ms) { if (player != null) player.seekTo(ms); }
    public synchronized void release() {
        if (player != null) { player.release(); player = null; }
    }

    /** "st,pos,dur,w,h,f" — same shape the stage JS expects from PX.getState().
     * v1.0.15: NEVER throws, from ANY thread. ExoPlayer 2.19.1 throws
     * IllegalStateException from getPlaybackState()/isPlaying() when called
     * off the application thread — and RemoteServer answers /api/state from
     * a pool thread, so the live calls killed the whole state reply ({}).
     * The listener-maintained lastState is the state source now. */
    public synchronized String getState() {
        try {
            if (player == null) return lastState + ",0,0,0,0,";
            int w = 0, h = 0;
            try {
                if (player.getVideoSize() != null) {
                    w = player.getVideoSize().width;
                    h = player.getVideoSize().height;
                }
            } catch (Exception ignored) { }
            long pos = 0, dur = 0;
            try { pos = player.getCurrentPosition(); dur = player.getDuration(); } catch (Exception ignored) { }
            if (dur == C.TIME_UNSET) dur = 0;
            int st = lastState;
            String f = (currentId == null ? "" : currentId) + "|" + (currentTitle == null ? "" : currentTitle);
            return st + "," + pos + "," + dur + "," + w + "," + h + "," + f;
        } catch (Throwable t) {
            return lastState + ",0,0,0,0,";
        }
    }

    public String getCurrentId() { return currentId; }
    public String getLastError() { return lastError; }
}
