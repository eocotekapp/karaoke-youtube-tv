package vn.karaoke.youtube;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube InnerTube client (custom, NOT SmartTube/NewPipe/ReVanced).
 * Primary: youtubei/v1/search + /player, client ANDROID.
 * Resolve: VISIONOS HLS first (v1.0.31, the original Gathin mechanism),
 * then Invidious HLS, then progressive itag 18 last.
 * Fallbacks: oEmbed + Invidious-compatible instances.
 * Clean rewrite from the behaviour spec.
 */
public class YoutubeApi {
    public static final String UA = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip";
    public static final String CLIENT_VERSION = "21.26.364";
    /** v1.0.31: VISIONOS client (the ORIGINAL Gathin mechanism, decoded
     * from tvhome-new.apk classes3.dex YoutubeApi.innertubePlayer /
     * playerBody / postJson). Safari UA + numeric client id 101.
     * QUIRK (kept verbatim — verified live WITH it): osName/osVersion
     * are "Android"/"11" even for VISIONOS (the original sets
     * visionOS/26.5.23O471 first, then unconditionally overwrites
     * with Android/11). */
    public static final String VISIONOS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String VISIONOS_CLIENT_VERSION = "1.02";
    private static final String[] INVIDIOUS = {
        "https://invidious.ducks.party",
        "https://invidious.materialio.us",
        "https://yt.chocolatemoo53.com"
    };
    private static final long DIRECT_TTL_MS = 10 * 60 * 1000;
    private static final long URL_TTL_MS = 60 * 60 * 1000;

    private static class CacheEntry { String json; long until; CacheEntry(String j, long u) { json = j; until = u; } }
    private final java.util.Map<String, CacheEntry> directCache = new java.util.HashMap<>();
    private final java.util.Map<String, CacheEntry> urlCache = new java.util.HashMap<>();
    public volatile String visitorData = "";
    private volatile long deadUntil = 0;

    public static class Video {
        public String id, title, channel, thumb;
        public long durationMs;
        public String durationText;
        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("title", title == null ? "" : title);
                o.put("channel", channel == null ? "" : channel);
                // v1.0.29: the real Gathin panel reads artist + duration
                // (seconds) from search results, like it does from queue items.
                o.put("artist", channel == null ? "" : channel);
                o.put("thumb", thumb == null ? ("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg") : thumb);
                o.put("durationMs", durationMs);
                o.put("duration", durationMs / 1000);
                o.put("durationText", durationText == null ? "" : durationText);
                return o;
            } catch (Exception e) { return new JSONObject(); }
        }
    }

    private String fetchText(String urlStr, String postJson) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8");
        if (postJson != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Goog-Api-Format-Version", "2");
            byte[] b = postJson.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b); os.flush(); os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + sb.toString());
        return sb.toString();
    }

    private JSONObject innertube(String path, JSONObject body) throws Exception {
        if (System.currentTimeMillis() < deadUntil) throw new Exception("innertube cooling down");
        try {
            String s = fetchText("https://www.youtube.com/youtubei/v1/" + path + "?prettyPrint=false", body.toString());
            JSONObject o = new JSONObject(s);
            if (o.has("responseContext")) {
                JSONObject vc = o.optJSONObject("responseContext");
                if (vc != null) visitorData = vc.optString("visitorData", visitorData);
            }
            return o;
        } catch (Exception e) {
            deadUntil = System.currentTimeMillis() + 60 * 1000;
            throw e;
        }
    }

    /** v1.0.31: POST to youtubei/v1/player as the VISIONOS client.
     * Headers replicate the original Gathin call verbatim (decoded
     * from tvhome-new.apk): Safari UA, X-YouTube-Client-Name "101",
     * X-YouTube-Client-Version "1.02", X-Goog-Visitor-Id when a
     * visitorData is supplied. NOTE: no X-Goog-Api-Format-Version
     * here — the original does not send it on this path, and the
     * live verification (2026-11-28) used exactly these headers. */
    private String fetchVisionos(String postJson, String visitor) throws Exception {
        URL url = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", VISIONOS_UA);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-YouTube-Client-Name", "101");
        c.setRequestProperty("X-YouTube-Client-Version", VISIONOS_CLIENT_VERSION);
        if (visitor != null && !visitor.isEmpty()) c.setRequestProperty("X-Goog-Visitor-Id", visitor);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        byte[] b = postJson.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(b.length);
        OutputStream os = c.getOutputStream();
        os.write(b); os.flush(); os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        if (code >= 400) throw new Exception("HTTP " + code + ": " + sb.toString());
        return sb.toString();
    }

    /** v1.0.31: the VISIONOS player body, verbatim from the original
     * (playerBody in the Gathin dex). visitorData is included in the
     * client object only when non-null. The osName/osVersion quirk
     * ("Android"/"11" for a VISIONOS client) is intentional — see the
     * VISIONOS_UA comment above. */
    private static JSONObject visionosBody(String videoId, String visitor) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "VISIONOS");
        client.put("clientVersion", VISIONOS_CLIENT_VERSION);
        client.put("hl", "en");
        client.put("gl", "US");
        client.put("clientScreen", "WATCH");
        client.put("deviceMake", "Apple");
        client.put("deviceModel", "RealityDevice17,1");
        client.put("osName", "Android");
        client.put("osVersion", "11");
        client.put("androidSdkVersion", 30);
        client.put("userAgent", VISIONOS_UA);
        if (visitor != null && !visitor.isEmpty()) client.put("visitorData", visitor);
        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        JSONObject body = new JSONObject();
        body.put("context", ctx);
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        return body;
    }

    /** Refresh the cached visitorData from a player response (the
     * original keeps it in a YoutubeApi field and reuses it). */
    private void updateVisitorData(JSONObject r) {
        if (r == null) return;
        JSONObject vc = r.optJSONObject("responseContext");
        if (vc != null) {
            String vd = vc.optString("visitorData", "");
            if (!vd.isEmpty()) visitorData = vd;
        }
    }

    /** Does this player response carry a usable HLS manifest URL? */
    private static boolean hasHlsManifest(JSONObject r) {
        if (r == null) return false;
        JSONObject sd = r.optJSONObject("streamingData");
        if (sd == null) return false;
        String hls = sd.optString("hlsManifestUrl", null);
        return hls != null && !hls.isEmpty();
    }

    /** v1.0.31: the original Gathin two-call visitorData dance
     * (innertubePlayer(videoId, VISIONOS=true) in the Gathin dex).
     * Call 1 sends the cached visitorData when the instance has one
     * (later resolves reuse it). If call 1 already returns a usable
     * HLS manifest, use it — no forced call 2. Otherwise, if call 1
     * yielded a FRESH visitorData (different from what call 1 sent),
     * call 2 sends it (body + X-Goog-Visitor-Id header). Verified
     * live 2026-11-28: call 1 without visitorData -> LOGIN_REQUIRED
     * but responseContext carries visitorData; call 2 with it -> OK +
     * hlsManifestUrl (manifest.googlevideo.com) + ALL adaptiveFormats
     * with direct URLs (jkVlEvuGjg8 23/23, 4s7OVBc6HBo 30/30). If
     * call 1 was sent WITH visitorData and still came back
     * LOGIN_REQUIRED with the same visitorData, a call 2 would be
     * identical — return the call-1 response and let the caller fall
     * through to Invidious. Throws on HTTP errors; the caller
     * (resolveVisionosHls) converts that to null. */
    private JSONObject innertubePlayerVisionOS(String videoId) throws Exception {
        String sentVd = visitorData; // what call 1 will send (may be "")
        JSONObject body = visionosBody(videoId, sentVd.isEmpty() ? null : sentVd);
        JSONObject r = new JSONObject(fetchVisionos(body.toString(), sentVd.isEmpty() ? null : sentVd));
        updateVisitorData(r);
        if (hasHlsManifest(r)) return r;
        String freshVd = visitorData;
        if (!freshVd.isEmpty() && !freshVd.equals(sentVd)) {
            JSONObject body2 = visionosBody(videoId, freshVd);
            JSONObject r2 = new JSONObject(fetchVisionos(body2.toString(), freshVd));
            updateVisitorData(r2);
            return r2;
        }
        return r;
    }

    /** v1.0.31: VISIONOS HLS manifest URL for a video, or null.
     * Never throws — any failure (HTTP error, no manifest, no
     * visitorData) just means the caller falls through to Invidious. */
    public String resolveVisionosHls(String videoId) {
        try {
            JSONObject r = innertubePlayerVisionOS(videoId);
            if (hasHlsManifest(r)) {
                String hls = r.optJSONObject("streamingData").optString("hlsManifestUrl");
                StartupLog.log("Resolve " + videoId + " visionos: co hlsManifestUrl");
                return hls;
            }
        } catch (Exception e) {
            StartupLog.log("Resolve " + videoId + " visionos LOI: " + e);
        }
        return null;
    }

    private JSONObject clientContext() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID");
        client.put("clientVersion", CLIENT_VERSION);
        client.put("androidSdkVersion", 30);
        client.put("userAgent", UA);
        client.put("hl", "vi");
        client.put("gl", "VN");
        if (!visitorData.isEmpty()) client.put("visitorData", visitorData);
        JSONObject ctx = new JSONObject();
        ctx.put("client", client);
        JSONObject body = new JSONObject();
        body.put("context", ctx);
        return body;
    }

    private static String runsText(JSONObject o) {
        if (o == null) return "";
        if (o.has("simpleText")) return o.optString("simpleText");
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) sb.append(runs.optJSONObject(i).optString("text"));
        return sb.toString();
    }

    private static long parseDuration(String t) {
        if (t == null || t.isEmpty()) return 0;
        String[] p = t.split(":");
        long s = 0;
        try {
            for (String x : p) s = s * 60 + Long.parseLong(x.trim());
        } catch (Exception ignored) { }
        return s * 1000;
    }

    private static String bestThumb(JSONObject th) {
        if (th == null) return null;
        JSONArray arr = th.optJSONArray("thumbnails");
        if (arr == null || arr.length() == 0) return null;
        return arr.optJSONObject(arr.length() - 1).optString("url");
    }

    /** Search videos. Returns list of Video. Falls back to Invidious on InnerTube failure. */
    public List<Video> search(String query) throws Exception {
        List<Video> out = new ArrayList<>();
        try {
            JSONObject body = clientContext();
            body.put("query", query);
            JSONObject r = innertube("search", body);
            JSONObject contents = r.optJSONObject("contents");
            if (contents != null) {
                JSONObject tc = contents.optJSONObject("twoColumnSearchResultsRenderer");
                if (tc != null) {
                    JSONObject pc = tc.optJSONObject("primaryContents");
                    if (pc != null) {
                        JSONObject sl = pc.optJSONObject("sectionListRenderer");
                        if (sl != null) {
                            JSONArray sections = sl.optJSONArray("contents");
                            if (sections != null) for (int i = 0; i < sections.length(); i++) {
                                JSONObject sec = sections.optJSONObject(i);
                                if (sec == null) continue;
                                JSONObject isr = sec.optJSONObject("itemSectionRenderer");
                                if (isr == null) continue;
                                JSONArray items = isr.optJSONArray("contents");
                                if (items == null) continue;
                                for (int j = 0; j < items.length(); j++) {
                                    JSONObject vr = items.optJSONObject(j).optJSONObject("videoRenderer");
                                    if (vr == null) continue;
                                    Video v = new Video();
                                    v.id = vr.optString("videoId");
                                    v.title = runsText(vr.optJSONObject("title"));
                                    v.channel = runsText(vr.optJSONObject("longBylineText"));
                                    if (v.channel.isEmpty()) v.channel = runsText(vr.optJSONObject("ownerText"));
                                    v.durationText = runsText(vr.optJSONObject("lengthText"));
                                    v.durationMs = parseDuration(v.durationText);
                                    v.thumb = bestThumb(vr.optJSONObject("thumbnail"));
                                    if (v.id != null && !v.id.isEmpty()) out.add(v);
                                }
                            }
                        }
                    }
                }
            }
            if (!out.isEmpty()) {
                StartupLog.log("Tim '" + query + "' -> " + out.size() + " ket qua (InnerTube)");
                return out;
            }
        } catch (Exception e) {
            StartupLog.log("Tim '" + query + "' InnerTube LOI: " + e + " -> thu Invidious");
            // fall through to Invidious
        }
        return searchInvidious(query);
    }

    private List<Video> searchInvidious(String query) throws Exception {
        for (String host : INVIDIOUS) {
            try {
                String s = fetchText(host + "/api/v1/search?type=video&q=" + URLEncoder.encode(query, "UTF-8"), null);
                JSONArray arr = new JSONArray(s);
                List<Video> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Video v = new Video();
                    v.id = o.optString("videoId");
                    v.title = o.optString("title");
                    v.channel = o.optString("author");
                    v.durationMs = o.optLong("lengthSeconds") * 1000;
                    v.durationText = o.optString("lengthText", "");
                    v.thumb = "https://i.ytimg.com/vi/" + v.id + "/hqdefault.jpg";
                    if (!v.id.isEmpty()) out.add(v);
                }
                if (!out.isEmpty()) {
                    StartupLog.log("Tim '" + query + "' -> " + out.size() + " ket qua (Invidious " + host + ")");
                    return out;
                }
            } catch (Exception ignored) { }
        }
        StartupLog.log("Tim '" + query + "' THAT BAI tren moi nguon");
        LogUploader.send("SEARCH-ERR");
        throw new Exception("search failed on all sources");
    }

    public JSONObject oEmbedItem(String videoId) {
        try {
            String s = fetchText("https://www.youtube.com/oembed?url=" +
                    URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8") + "&format=json", null);
            return new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    /**
     * Resolve a videoId to playable candidates.
     * Returns JSONObject: {id, title, durationMs, candidates:[{url, kind, proxied}]}
     * kind: "progressive" (direct mp4) or "hls" (manifest/.m3u8).
     */
    public JSONObject resolve(String videoId) throws Exception {
        String cached = getCached(directCache, videoId);
        if (cached != null) return new JSONObject(cached);
        JSONObject result = new JSONObject();
        result.put("id", videoId);
        try {
            JSONObject body = clientContext();
            body.put("videoId", videoId);
            body.put("playbackContext", new JSONObject().put("contentPlaybackContext",
                    new JSONObject().put("html5Preference", "HTML5_PREF_WANTS")));
            JSONObject r = innertube("player", body);
            JSONObject vd = r.optJSONObject("videoDetails");
            if (vd != null) {
                result.put("title", vd.optString("title"));
                result.put("channel", vd.optString("author"));
                result.put("durationMs", vd.optLong("lengthSeconds") * 1000);
            }
            JSONObject sd = r.optJSONObject("streamingData");
            JSONArray cands = new JSONArray();
            if (sd != null) {
                // progressive mp4 first: itag 22 (720p), 18 (360p), 37, 59
                JSONArray fmts = sd.optJSONArray("formats");
                if (fmts != null) {
                    int[] prefer = {22, 18, 37, 59, 43};
                    for (int want : prefer) {
                        for (int i = 0; i < fmts.length(); i++) {
                            JSONObject f = fmts.optJSONObject(i);
                            if (f.optInt("itag") == want && f.has("url")) {
                                JSONObject c = new JSONObject();
                                c.put("url", f.optString("url"));
                                c.put("kind", "progressive");
                                c.put("itag", want);
                                cands.put(c);
                            }
                        }
                        if (cands.length() > 0) break;
                    }
                }
                String hls = sd.optString("hlsManifestUrl", null);
                if (hls != null && !hls.isEmpty()) {
                    JSONObject c = new JSONObject();
                    c.put("url", hls);
                    c.put("kind", "hls");
                    cands.put(c);
                }
            }
            result.put("candidates", cands);
            // v1.0.31: VISIONOS HLS FIRST — the original Gathin mechanism
            // (decoded from tvhome-new.apk): two-call visitorData dance,
            // client 101 / 1.02, returns a real 16-variant HLS ladder
            // with AVC 1080p/1080p60. Verified live 2026-11-28:
            // jkVlEvuGjg8 -> AVC 1920x1080@24 (BANDWIDTH=4420108),
            // 4s7OVBc6HBo -> AVC 1920x1080@60 (BANDWIDTH=6293353).
            // Invidious HLS stays SECOND (v1.0.30), progressive itag 18
            // LAST. A dead VISIONOS + dead Invidious is no regression
            // (360p as today). InnerTube ANDROID only ever returns itag
            // 18 (640x360) with a playable URL — itag 22 is absent and
            // every adaptiveFormat carries NEITHER url NOR
            // signatureCipher (no poToken) — so this upgrade runs
            // whenever the best progressive is itag 18 (or none).
            if (cands.length() == 0 || bestIsItag18(cands)) {
                String visHls = resolveVisionosHls(videoId);
                JSONObject inv = hlsInvidious(videoId);
                JSONArray merged = new JSONArray();
                if (visHls != null) {
                    JSONObject c = new JSONObject();
                    c.put("url", visHls);
                    c.put("kind", "hls");
                    c.put("source", "visionos");
                    merged.put(c);
                }
                if (inv != null) {
                    JSONArray invCands = inv.optJSONArray("candidates");
                    if (invCands != null && invCands.length() > 0) {
                        JSONObject first = invCands.optJSONObject(0);
                        if (first != null && "hls".equals(first.optString("kind"))) {
                            merged.put(first); // second: Invidious HLS
                        } else if (merged.length() == 0 && cands.length() == 0) {
                            // no visionos, no progressive: use Invidious as-is
                            for (int i = 0; i < invCands.length(); i++) merged.put(invCands.optJSONObject(i));
                        }
                    }
                    if (result.optString("title", "").isEmpty()) {
                        result.put("title", inv.optString("title", ""));
                    }
                }
                for (int i = 0; i < cands.length(); i++) merged.put(cands.optJSONObject(i));
                if (merged.length() > 0) { cands = merged; result.put("candidates", cands); }
            }
        } catch (Exception e) {
            // ANDROID player call failed (network/cooling) — the VISIONOS
            // dance is a separate endpoint, so try it before Invidious.
            JSONArray fb = new JSONArray();
            String visHls = resolveVisionosHls(videoId);
            if (visHls != null) {
                JSONObject c = new JSONObject();
                c.put("url", visHls);
                c.put("kind", "hls");
                c.put("source", "visionos");
                fb.put(c);
            }
            JSONObject inv = hlsInvidious(videoId);
            if (inv != null) {
                result.put("title", inv.optString("title", ""));
                JSONArray invCands = inv.optJSONArray("candidates");
                if (invCands != null) for (int i = 0; i < invCands.length(); i++) fb.put(invCands.optJSONObject(i));
            }
            if (fb.length() > 0) { result.put("candidates", fb); }
            else {
                StartupLog.log("Resolve " + videoId + " LOI: " + e);
                throw e;
            }
        }
        if (result.optJSONArray("candidates") == null || result.optJSONArray("candidates").length() == 0) {
            JSONObject oe = oEmbedItem(videoId);
            if (oe != null) result.put("title", oe.optString("title", result.optString("title")));
            StartupLog.log("Resolve " + videoId + " THAT BAI: no playable stream");
            LogUploader.send("RESOLVE-ERR");
            throw new Exception("no playable stream for " + videoId);
        }
        // v1.0.30: NEVER cache a resolve whose chosen (first) candidate
        // is an HLS manifest. The manifest URL carries an expire=
        // timestamp and is minted per request/IP — a cached copy can go
        // stale (and ExoPlayer re-fetches the manifest on every play
        // anyway, so caching buys nothing here). Progressive-only
        // results keep the 10-min direct cache as before.
        JSONArray finalCands = result.optJSONArray("candidates");
        boolean firstIsHls = finalCands != null && finalCands.length() > 0
                && "hls".equals(finalCands.optJSONObject(0).optString("kind"));
        if (!firstIsHls) putCached(directCache, videoId, result.toString(), DIRECT_TTL_MS);
        // Name the chosen path in the log (first candidate is the one
        // MesuPlayer plays): e.g. "hls itag (invidious)" when the
        // Invidious manifest wins, "progressive itag18" otherwise.
        StringBuilder desc = new StringBuilder();
        if (finalCands != null) for (int i = 0; i < finalCands.length(); i++) {
            JSONObject c = finalCands.optJSONObject(i);
            if (c == null) continue;
            if (desc.length() > 0) desc.append(", ");
            desc.append(c.optString("kind"));
            if (c.has("itag")) desc.append(" itag").append(c.optString("itag"));
            if (c.has("source")) desc.append(" (").append(c.optString("source")).append(")");
        }
        StartupLog.log("Resolve " + videoId + " OK: " + finalCands.length()
                + " ung vien [" + desc + "], '" + result.optString("title") + "'");
        return result;
    }

    /** Best InnerTube progressive is itag 18 (640x360)? The prefer loop
     * breaks after the first itag with candidates, so every candidate
     * carries the same itag — checking the first is enough. */
    private static boolean bestIsItag18(JSONArray cands) {
        if (cands == null || cands.length() == 0) return false;
        JSONObject first = cands.optJSONObject(0);
        return first != null && first.optInt("itag", -1) == 18;
    }

    private JSONObject hlsInvidious(String videoId) {
        for (String host : INVIDIOUS) {
            try {
                String s = fetchText(host + "/api/v1/videos/" + videoId, null);
                JSONObject o = new JSONObject(s);
                JSONArray cands = new JSONArray();
                // v1.0.30: prefer the hlsUrl master manifest FIRST — it
                // carries every quality incl. 1080p (verified live: the
                // jkVlEvuGjg8 manifest has RESOLUTION=1920x1080 with
                // absolute youtube.com variant URIs). formatStreams tops
                // out at 720p and was EMPTY for the videos the user plays,
                // so it is only a fallback when the instance returns no
                // hlsUrl. "source" tags the candidate for the resolve log.
                String hls = o.optString("hlsUrl", null);
                if (hls != null && !hls.isEmpty()) {
                    JSONObject c = new JSONObject();
                    c.put("url", hls.startsWith("http") ? hls : host + hls);
                    c.put("kind", "hls");
                    c.put("source", "invidious");
                    cands.put(c);
                } else {
                    JSONArray fs = o.optJSONArray("formatStreams");
                    if (fs != null) {
                        for (int i = 0; i < fs.length(); i++) {
                            JSONObject f = fs.optJSONObject(i);
                            if (f.has("url")) {
                                JSONObject c = new JSONObject();
                                c.put("url", f.optString("url"));
                                c.put("kind", "progressive");
                                c.put("itag", f.optString("itag"));
                                c.put("source", "invidious");
                                cands.put(c);
                            }
                        }
                    }
                }
                if (cands.length() > 0) {
                    JSONObject r = new JSONObject();
                    r.put("title", o.optString("title"));
                    r.put("candidates", cands);
                    return r;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String getCached(java.util.Map<String, CacheEntry> m, String k) {
        CacheEntry e = m.get(k);
        if (e != null && System.currentTimeMillis() < e.until) return e.json;
        m.remove(k);
        return null;
    }
    private void putCached(java.util.Map<String, CacheEntry> m, String k, String j, long ttl) {
        m.put(k, new CacheEntry(j, System.currentTimeMillis() + ttl));
    }

    /** Extract a YouTube video id from watch/shorts/embed/live/youtu.be links. */
    public static String extractId(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.matches("[A-Za-z0-9_-]{11}")) return text;
        try {
            java.util.regex.Matcher m;
            m = java.util.regex.Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(text);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})").matcher(text);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile("/(shorts|embed|live|v)/([A-Za-z0-9_-]{11})").matcher(text);
            if (m.find()) return m.group(2);
        } catch (Exception ignored) { }
        return null;
    }
}
