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
        public String id, title, channel;
        public long durationMs;
        public Item(String id, String title, String channel, long durationMs) {
            this.id = id; this.title = title; this.channel = channel; this.durationMs = durationMs;
        }
        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("title", title == null ? "" : title);
                o.put("channel", channel == null ? "" : channel);
                o.put("durationMs", durationMs);
                return o;
            } catch (Exception e) { return new JSONObject(); }
        }
        public static Item fromJson(JSONObject o) {
            return new Item(o.optString("id"), o.optString("title"),
                    o.optString("channel"), o.optLong("durationMs"));
        }
    }

    public static class Playlist {
        public String name;
        public List<Item> items = new ArrayList<>();
        public Playlist(String name) { this.name = name; }
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
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                Playlist pl = new Playlist(p.optString("name"));
                JSONArray items = p.optJSONArray("items");
                if (items != null) for (int j = 0; j < items.length(); j++)
                    pl.items.add(Item.fromJson(items.getJSONObject(j)));
                playlists.add(pl);
            }
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
