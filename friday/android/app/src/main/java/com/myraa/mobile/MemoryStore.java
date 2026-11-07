package com.myraa.mobile;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/**
 * Long-term memory in a private SQLite database on this phone (the app has allowBackup=false).
 * Each memory has a category (profile, preference, project, context), timestamps and a use count.
 * Only memories relevant to the moment are given to the model, never the whole table, and every
 * memory can be viewed, edited and deleted from Settings or by voice.
 *
 * The app is built without Gradle or AndroidX, so this uses Android's own SQLite API (the same
 * database Room wraps) instead of Room.
 */
final class MemoryStore extends SQLiteOpenHelper {
  static final int MAX_MEMORIES = 300, MAX_SUMMARIES = 12;
  private static MemoryStore instance;
  private final Context context;

  static synchronized MemoryStore get(Context c) { if (instance == null) instance = new MemoryStore(c.getApplicationContext()); return instance; }
  private MemoryStore(Context c) { super(c, "friday-memory.db", null, 1); context = c; }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, text TEXT NOT NULL, norm TEXT NOT NULL, subject TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, updated INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0, uses INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE INDEX memories_category ON memories(category)");
    // Old memories were a plain list in preferences: move them in once, oldest first.
    SharedPreferences prefs = context.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE);
    try {
      JSONArray old = new JSONArray(prefs.getString("memories", "[]")); long now = System.currentTimeMillis();
      for (int i = 0; i < old.length(); i++) {
        String f = clean(old.optString(i)); if (f.isEmpty() || MemoryRank.secret(f)) continue;
        insert(db, MemoryRank.guessCategory(f), f, now - (old.length() - i), MemoryRank.sensitive(f));
      }
      prefs.edit().remove("memories").apply();
    } catch (JSONException ignored) {}
  }
  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {}

  static String clean(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }
  private static long insert(SQLiteDatabase db, String category, String text, long at, boolean sensitive) {
    ContentValues v = new ContentValues();
    v.put("category", category); v.put("text", text); v.put("norm", MemoryRank.normalize(text)); v.put("subject", MemoryRank.subjectKey(text));
    v.put("created", at); v.put("updated", at); v.put("sensitive", sensitive ? 1 : 0);
    return db.insert("memories", null, v);
  }

  static final class Saved { final String status, text; final long id; Saved(String status, long id, String text) { this.status = status; this.id = id; this.text = text; } }

  /**
   * Saves one fact. Returns "added", "same" (already known), "updated" (a near duplicate or an older value
   * of the same subject was replaced) or "needs_consent" (sensitive and not yet allowed by the user).
   */
  synchronized Saved save(String rawCategory, String fact, boolean allowSensitive) {
    String f = clean(fact);
    if (f.isEmpty() || f.length() > 500) throw new IllegalArgumentException("Tell me one short thing to remember.");
    if (MemoryRank.secret(f)) throw new IllegalArgumentException("I don't save passwords, PINs, OTPs or card numbers. Keep those to yourself.");
    boolean sensitive = MemoryRank.sensitive(f);
    if (sensitive && !allowSensitive) return new Saved("needs_consent", -1, f);
    String category = MemoryRank.category(rawCategory, f), norm = MemoryRank.normalize(f), subject = MemoryRank.subjectKey(f);
    SQLiteDatabase db = getWritableDatabase(); long now = System.currentTimeMillis();
    long match = -1; String why = "";
    try (Cursor c = db.query("memories", new String[]{"id", "norm", "subject", "text"}, "category=?", new String[]{category}, null, null, "updated DESC")) {
      while (c.moveToNext()) {
        String n = c.getString(1), s = c.getString(2);
        if (n.equals(norm)) { match = c.getLong(0); why = "same"; break; }
        if (!subject.isEmpty() && subject.equals(s)) { match = c.getLong(0); why = "updated"; break; }
        if (!isContext(category) && MemoryRank.similarity(f, c.getString(3)) >= 0.75) { match = c.getLong(0); why = "updated"; break; }
      }
    }
    if (match >= 0) {
      ContentValues v = new ContentValues(); v.put("updated", now);
      if ("updated".equals(why)) { v.put("text", f); v.put("norm", norm); v.put("subject", subject); v.put("sensitive", sensitive ? 1 : 0); }
      db.update("memories", v, "id=?", new String[]{String.valueOf(match)});
      return new Saved(why, match, f);
    }
    long id = insert(db, category, f, now, sensitive);
    trim(db);
    return new Saved("added", id, f);
  }
  /** Conversation summaries are expected to look alike, so they are never merged by similarity. */
  private static boolean isContext(String category) { return MemoryRank.CONTEXT.equals(category); }

  /** Keeps the newest summaries and the overall size bounded; the least used, oldest go first. */
  private void trim(SQLiteDatabase db) {
    db.execSQL("DELETE FROM memories WHERE category='context' AND id NOT IN (SELECT id FROM memories WHERE category='context' ORDER BY updated DESC LIMIT " + MAX_SUMMARIES + ")");
    db.execSQL("DELETE FROM memories WHERE id NOT IN (SELECT id FROM memories ORDER BY uses DESC, updated DESC LIMIT " + MAX_MEMORIES + ")");
  }

  synchronized boolean edit(long id, String text, String rawCategory) {
    String f = clean(text);
    if (f.isEmpty() || f.length() > 500) throw new IllegalArgumentException("A memory must be between 1 and 500 characters.");
    if (MemoryRank.secret(f)) throw new IllegalArgumentException("I don't save passwords, PINs, OTPs or card numbers.");
    ContentValues v = new ContentValues();
    v.put("text", f); v.put("norm", MemoryRank.normalize(f)); v.put("subject", MemoryRank.subjectKey(f)); v.put("updated", System.currentTimeMillis()); v.put("sensitive", MemoryRank.sensitive(f) ? 1 : 0);
    if (rawCategory != null && !rawCategory.isEmpty()) v.put("category", MemoryRank.category(rawCategory, f));
    return getWritableDatabase().update("memories", v, "id=?", new String[]{String.valueOf(id)}) > 0;
  }
  synchronized boolean delete(long id) { return getWritableDatabase().delete("memories", "id=?", new String[]{String.valueOf(id)}) > 0; }
  synchronized int deleteMatching(String query) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (q.equals("everything") || q.equals("all") || q.equals("sab kuch") || q.equals("sab")) return getWritableDatabase().delete("memories", null, null);
    int removed = 0;
    for (JSONObject m : all()) if (m.optString("text").toLowerCase(Locale.ROOT).contains(q)) removed += delete(m.optLong("id")) ? 1 : 0;
    return removed;
  }
  synchronized int deleteCategory(String category) { return getWritableDatabase().delete("memories", "category=?", new String[]{category}); }

  /** Every memory, oldest first, for Settings, backups and "what do you remember". */
  synchronized List<JSONObject> all() {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("memories", new String[]{"id", "category", "text", "updated", "uses", "sensitive"}, null, null, null, null, "id ASC")) {
      while (c.moveToNext()) out.add(row(c));
    }
    return out;
  }
  private static JSONObject row(Cursor c) {
    JSONObject v = new JSONObject();
    try { v.put("id", c.getLong(0)).put("category", c.getString(1)).put("text", c.getString(2)).put("updated", c.getLong(3)).put("uses", c.getInt(4)).put("sensitive", c.getInt(5) == 1); } catch (JSONException ignored) {}
    return v;
  }

  /**
   * The memories worth giving the model now: the best matches for the query plus a few basics,
   * within a character budget. Marks them as used so they rank a little higher next time.
   */
  synchronized List<JSONObject> relevant(String query, int limit, int charBudget, boolean includeSummaries) {
    Set<String> q = MemoryRank.tokens(query); long now = System.currentTimeMillis();
    List<Object[]> scored = new ArrayList<>();
    try (Cursor c = getReadableDatabase().query("memories", new String[]{"id", "category", "text", "updated", "uses", "sensitive"}, null, null, null, null, null)) {
      while (c.moveToNext()) {
        String cat = c.getString(1);
        if (MemoryRank.CONTEXT.equals(cat) && !includeSummaries) continue;
        double s = MemoryRank.score(q, c.getString(2), cat, c.getLong(3), c.getInt(4), now);
        // Sensitive facts come up only when the conversation is about them.
        if (c.getInt(5) == 1 && s < 3) continue;
        scored.add(new Object[]{ s, row(c) });
      }
    }
    Collections.sort(scored, (a, b) -> Double.compare((Double) b[0], (Double) a[0]));
    List<JSONObject> out = new ArrayList<>(); int chars = 0;
    for (Object[] e : scored) {
      JSONObject m = (JSONObject) e[1]; int len = m.optString("text").length();
      if (out.size() >= limit || chars + len > charBudget) continue;
      out.add(m); chars += len;
    }
    if (!out.isEmpty()) {
      StringBuilder ids = new StringBuilder();
      for (JSONObject m : out) { if (ids.length() > 0) ids.append(','); ids.append(m.optLong("id")); }
      getWritableDatabase().execSQL("UPDATE memories SET uses=uses+1, used=" + now + " WHERE id IN (" + ids + ")");
    }
    return out;
  }
  /** The most recently updated memory in a category, or null. */
  synchronized JSONObject latest(String category) {
    try (Cursor c = getReadableDatabase().query("memories", new String[]{"id", "category", "text", "updated", "uses", "sensitive"}, "category=? AND sensitive=0", new String[]{category}, null, null, "updated DESC", "1")) {
      return c.moveToFirst() ? row(c) : null;
    }
  }
  synchronized int count() { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories", null)) { return c.moveToFirst() ? c.getInt(0) : 0; } }
}
