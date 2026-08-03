package com.mavve.myactionbar.memory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis's long-term memory and self-knowledge, in one SQLite file.
 *
 * Two tables:
 *  - memories: everything worth keeping across sessions — user facts and
 *    preferences, conversation summaries, results produced by sub-agents.
 *    Agents read it through the recall tool before asking the user anything,
 *    and write it through the remember tool.
 *  - model_scores: the outcome ledger behind the model router. Every agent
 *    run records (model, task category, success). The router reads win rates
 *    from here, which is how "the best model for X gets used for X" stays
 *    true as models and tasks evolve.
 *
 * Retrieval is keyword-plus-recency scoring. It is deliberately simple and
 * fully on-device; see docs/JARVIS.md for the embedding-based upgrade path.
 */
public class ContextDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "jarvis.db";
    private static final int DB_VERSION = 1;

    public static class Memory {
        public final long id;
        public final String kind;
        public final String content;
        public final String tags;
        public final long createdAt;

        Memory(long id, String kind, String content, String tags, long createdAt) {
            this.id = id;
            this.kind = kind;
            this.content = content;
            this.tags = tags;
            this.createdAt = createdAt;
        }
    }

    public ContextDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memories ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "kind TEXT NOT NULL, "
                + "content TEXT NOT NULL, "
                + "tags TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL, "
                + "use_count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE model_scores ("
                + "model TEXT NOT NULL, "
                + "task TEXT NOT NULL, "
                + "wins REAL NOT NULL DEFAULT 0, "
                + "tries REAL NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (model, task))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 — nothing to migrate yet.
    }

    // ---- memories ---------------------------------------------------------

    public long remember(String kind, String content, String tags) {
        ContentValues values = new ContentValues();
        values.put("kind", kind == null ? "note" : kind);
        values.put("content", content);
        values.put("tags", tags == null ? "" : tags);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("memories", null, values);
    }

    /**
     * Keyword search scored by term overlap with a recency boost. Marks
     * returned rows as used so frequently-useful memories can be preferred
     * later.
     */
    public List<Memory> recall(String query, int limit) {
        List<Memory> all = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, kind, content, tags, created_at FROM memories "
                        + "ORDER BY created_at DESC LIMIT 500", null)) {
            while (cursor.moveToNext()) {
                all.add(new Memory(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
            }
        }
        String[] terms = query == null ? new String[0]
                : query.toLowerCase(Locale.ROOT).split("\\W+");
        List<Memory> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Memory memory : all) {
            String haystack = (memory.content + " " + memory.tags + " " + memory.kind)
                    .toLowerCase(Locale.ROOT);
            double score = 0;
            for (String term : terms) {
                if (term.length() > 2 && haystack.contains(term)) {
                    score += 1;
                }
            }
            if (score == 0 && terms.length > 0) {
                continue;
            }
            double ageDays = (now - memory.createdAt) / 86_400_000.0;
            score += 1.0 / (1.0 + ageDays); // recency boost
            int insertAt = scored.size();
            for (int i = 0; i < scores.size(); i++) {
                if (score > scores.get(i)) {
                    insertAt = i;
                    break;
                }
            }
            scored.add(insertAt, memory);
            scores.add(insertAt, score);
        }
        List<Memory> top = scored.subList(0, Math.min(limit, scored.size()));
        for (Memory memory : top) {
            getWritableDatabase().execSQL(
                    "UPDATE memories SET use_count = use_count + 1 WHERE id = ?",
                    new Object[]{memory.id});
        }
        return new ArrayList<>(top);
    }

    public List<Memory> recent(int limit) {
        List<Memory> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, kind, content, tags, created_at FROM memories "
                        + "ORDER BY created_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                result.add(new Memory(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getLong(4)));
            }
        }
        return result;
    }

    // ---- model scorecards -------------------------------------------------

    public void recordModelOutcome(String model, String task, boolean success) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO model_scores (model, task, wins, tries) "
                + "VALUES (?, ?, 0, 0)", new Object[]{model, task});
        db.execSQL("UPDATE model_scores SET wins = wins + ?, tries = tries + 1 "
                + "WHERE model = ? AND task = ?",
                new Object[]{success ? 1.0 : 0.0, model, task});
    }

    /**
     * Learned win rate for a model on a task category, pulled toward 0.5 when
     * there is little data so a lucky first run does not dominate the priors.
     */
    public double learnedScore(String model, String task) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT wins, tries FROM model_scores WHERE model = ? AND task = ?",
                new String[]{model, task})) {
            if (cursor.moveToFirst()) {
                double wins = cursor.getDouble(0);
                double tries = cursor.getDouble(1);
                return (wins + 2.5) / (tries + 5.0);
            }
        }
        return 0.5;
    }

    public String scoreboard() {
        StringBuilder sb = new StringBuilder();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT model, task, wins, tries FROM model_scores ORDER BY model, task",
                null)) {
            while (cursor.moveToNext()) {
                sb.append(cursor.getString(0)).append(" / ").append(cursor.getString(1))
                        .append(": ").append((int) cursor.getDouble(2)).append('/')
                        .append((int) cursor.getDouble(3)).append(" wins\n");
            }
        }
        return sb.length() == 0 ? "No outcomes recorded yet." : sb.toString();
    }
}
