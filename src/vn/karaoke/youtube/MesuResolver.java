package vn.karaoke.youtube;

import org.json.JSONObject;

/** Single-method resolver used by the stage JS bridge (MR.resolve). Clean rewrite. */
public class MesuResolver {
    private final YoutubeApi api = new YoutubeApi();

    /** Resolve a video id to a JSON string the stage can play. */
    public String resolve(String videoId) {
        try {
            JSONObject r = api.resolve(videoId);
            return r.toString();
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("id", videoId);
                err.put("error", e.getMessage() == null ? "resolve failed" : e.getMessage());
                err.put("candidates", new org.json.JSONArray());
                return err.toString();
            } catch (Exception ignored) { return "{\"error\":\"resolve failed\"}"; }
        }
    }
}
