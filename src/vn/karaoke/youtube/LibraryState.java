package vn.karaoke.youtube;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Queue + local playlists, persisted in SharedPreferences. Clean rewrite. */
public class LibraryState {
    public static class Item {
        public String id, title, channel, thumb;
        public long durationMs;
        public Item(String id, String title, String channel, long durationMs) {
            this(id, title, channel, durationMs, null);
        }
        public Item(String id, String title, String channel, long durationMs, String thumb) {
            this.id = id; this.title = title; this.channel = channel; this.durationMs = durationMs; this.thumb = thumb;
        }
        /** v1.0.29: the real Gathin panel reads artist/thumb/duration (seconds)
         * from every item (current, queue, playlist, search) — expose them as
         * aliases alongside the existing id/title/channel/durationMs fields. */
        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("title", title == null ? "" : title);
                o.put("channel", channel == null ? "" : channel);
                o.put("artist", channel == null ? "" : channel);
                o.put("thumb", (thumb == null || thumb.isEmpty())
                        ? ("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg") : thumb);
                o.put("durationMs", durationMs);
                o.put("duration", durationMs / 1000);
                return o;
            } catch (Exception e) { return new JSONObject(); }
        }
        public static Item fromJson(JSONObject o) {
            return new Item(o.optString("id"), o.optString("title"),
                    o.optString("channel"), o.optLong("durationMs"),
                    o.has("thumb") ? o.optString("thumb") : null);
        }
    }

    /** v1.0.29: playlists now carry a stable id (the real Gathin panel
     * addresses playlists by pl.id, never by name). The id is generated
     * once on creation and persisted with the playlist; playlists saved
     * by older builds (name only) get an id assigned on load (see load()). */
    public static class Playlist {
        public String id, name;
        public List<Item> items = new ArrayList<>();
        public Playlist(String name) { this(java.util.UUID.randomUUID().toString(), name); }
        public Playlist(String id, String name) {
            this.id = (id == null || id.isEmpty()) ? java.util.UUID.randomUUID().toString() : id;
            this.name = name;
        }
    }

    private static final String PREFS = "karaoke_library";
    private final SharedPreferences prefs;
    public final List<Item> queue = new ArrayList<>();
    public int currentIndex = -1;
    public final List<Playlist> playlists = new ArrayList<>();
    public final List<String> searchHistory = new ArrayList<>();

    public LibraryState(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        try {
            JSONArray q = new JSONArray(prefs.getString("queue", "[]"));
            for (int i = 0; i < q.length(); i++) queue.add(Item.fromJson(q.getJSONObject(i)));
            currentIndex = prefs.getInt("currentIndex", queue.isEmpty() ? -1 : 0);
            JSONArray ps = new JSONArray(prefs.getString("playlists", "[]"));
            boolean migrated = false;
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                Playlist pl = new Playlist(p.optString("id"), p.optString("name"));
                if (!p.has("id") || p.optString("id").isEmpty()) migrated = true;
                JSONArray items = p.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++)
                    pl.items.add(Item.fromJson(items.getJSONObject(j)));
                playlists.add(pl);
            }
            if (migrated) persist(); // v1.0.29: old name-only playlists get a stable id, saved at once
            JSONArray h = new JSONArray(prefs.getString("searchHistory", "[]"));
            for (int i = 0; i < h.length(); i++) searchHistory.add(h.optString(i));
        } catch (Exception ignored) { }
    }

    public synchronized void persist() {
        try {
            JSONArray q = new JSONArray();
            for (Item it : queue) q.put(it.toJson());
            JSONArray ps = new JSONArray();
            for (Playlist pl : playlists) {
                JSONObject p = new JSONObject();
                p.put("id", pl.id);
                p.put("name", pl.name);
                JSONArray items = new JSONArray();
                for (Item it : pl.items) items.put(it.toJson());
                p.put("items", items);
                ps.put(p);
            }
            JSONArray h = new JSONArray();
            for (String s : searchHistory) h.put(s);
            prefs.edit().putString("queue", q.toString())
                    .putInt("currentIndex", currentIndex)
                    .putString("playlists", ps.toString())
                    .putString("searchHistory", h.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Back-compat names from the original behaviour spec. */
    public void persistKnown() { persist(); }
    public void recordPlay(Item it) {
        if (it == null) return;
        // keep queue as-is; just persist current position
        persist();
    }
    public void clearHistory() { searchHistory.clear(); persist(); }
    public void clearSearchHistory() { clearHistory(); }

    public synchronized void enqueue(Item it) { queue.add(it); if (currentIndex < 0) currentIndex = 0; persist(); }
    public synchronized void enqueueAtFront(Item it) { queue.add(0, it); currentIndex = 0; persist(); }
    public synchronized Item current() {
        if (currentIndex >= 0 && currentIndex < queue.size()) return queue.get(currentIndex);
        return null;
    }
    public synchronized Item next() {
        if (queue.isEmpty()) return null;
        currentIndex = (currentIndex + 1) % queue.size();
        persist();
        return current();
    }
    public synchronized Item prev() {
        if (queue.isEmpty()) return null;
        currentIndex = (currentIndex - 1 + queue.size()) % queue.size();
        persist();
        return current();
    }
    public synchronized void removeAt(int i) {
        if (i < 0 || i >= queue.size()) return;
        queue.remove(i);
        if (currentIndex >= queue.size()) currentIndex = queue.size() - 1;
        persist();
    }
    public synchronized void move(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size()) return;
        Item it = queue.remove(from);
        queue.add(to, it);
        persist();
    }
    public synchronized void jumpTo(int i) {
        if (i >= 0 && i < queue.size()) { currentIndex = i; persist(); }
    }
    public synchronized void clearQueue() { queue.clear(); currentIndex = -1; persist(); }

    public synchronized Playlist getOrCreatePlaylist(String name) {
        for (Playlist p : playlists) if (p.name.equals(name)) return p;
        Playlist p = new Playlist(name);
        playlists.add(p);
        persist();
        return p;
    }
    public synchronized void deletePlaylist(String name) {
        for (int i = 0; i < playlists.size(); i++)
            if (playlists.get(i).name.equals(name)) { playlists.remove(i); break; }
        persist();
    }
    /** v1.0.29: playlist lookup by the stable id the Gathin panel uses. */
    public synchronized Playlist findPlaylistById(String id) {
        if (id == null) return null;
        for (Playlist p : playlists) if (id.equals(p.id)) return p;
        return null;
    }
    public synchronized Playlist findPlaylistByName(String name) {
        if (name == null) return null;
        for (Playlist p : playlists) if (name.equals(p.name)) return p;
        return null;
    }
    public synchronized void deletePlaylistById(String id) {
        for (int i = 0; i < playlists.size(); i++)
            if (playlists.get(i).id.equals(id)) { playlists.remove(i); break; }
        persist();
    }
    /** v1.0.29: add an item to a playlist, skipping duplicates by video id.
     * Returns true only when the item was actually added (the panel toasts
     * "Bài đã có trong..." when it gets added==0). */
    public synchronized boolean playlistAdd(Playlist pl, Item it) {
        if (pl == null || it == null || it.id == null) return false;
        for (Item x : pl.items) if (it.id.equals(x.id)) return false;
        pl.items.add(it);
        persist();
        return true;
    }
    public synchronized void recordSearch(String q) {
        if (q == null || q.isEmpty()) return;
        searchHistory.remove(q);
        searchHistory.add(0, q);
        while (searchHistory.size() > 20) searchHistory.remove(searchHistory.size() - 1);
        persist();
    }

    public JSONObject export() {
        try {
            JSONObject o = new JSONObject();
            JSONArray q = new JSONArray();
            for (Item it : queue) q.put(it.toJson());
            o.put("queue", q);
            o.put("currentIndex", currentIndex);
            JSONArray ps = new JSONArray();
            for (Playlist pl : playlists) {
                JSONObject p = new JSONObject();
                p.put("id", pl.id);
                p.put("name", pl.name);
                JSONArray items = new JSONArray();
                for (Item it : pl.items) items.put(it.toJson());
                p.put("items", items);
                ps.put(p);
            }
            o.put("playlists", ps);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }
}
