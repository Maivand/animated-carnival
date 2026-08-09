package com.mavve.myactionbar.memory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Jarvis's second brain and self-knowledge, in one SQLite file.
 *
 * Tables:
 *  - memories: durable facts, preferences, results — each row optionally
 *    carries an embedding vector (BLOB) for semantic retrieval.
 *  - documents / doc_chunks: the RAG corpus. Every document the user loads is
 *    chunked and indexed here (with embeddings when a provider is
 *    configured), so agents can answer "what did that research say about X"
 *    by retrieving the few relevant chunks instead of ingesting the whole
 *    document.
 *  - model_scores: the outcome ledger behind the model router.
 *
 * Retrieval is hybrid: cosine similarity over embeddings when both the query
 * and the row have vectors, blended with keyword overlap and recency so the
 * system still works with no embedding provider at all.
 */
public class ContextDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "jarvis.db";
    private static final int DB_VERSION = 2;

    /** Weight of semantic similarity vs keyword overlap in hybrid scoring. */
    private static final double VECTOR_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

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

    public static class ChunkHit {
        public final String docTitle;
        public final int chunkIndex;
        public final String content;
        public final double score;

        ChunkHit(String docTitle, int chunkIndex, String content, double score) {
            this.docTitle = docTitle;
            this.chunkIndex = chunkIndex;
            this.content = content;
            this.score = score;
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
                + "use_count INTEGER NOT NULL DEFAULT 0, "
                + "embedding BLOB)");
        db.execSQL("CREATE TABLE model_scores ("
                + "model TEXT NOT NULL, "
                + "task TEXT NOT NULL, "
                + "wins REAL NOT NULL DEFAULT 0, "
                + "tries REAL NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (model, task))");
        createDocTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE memories ADD COLUMN embedding BLOB");
            createDocTables(db);
        }
    }

    private void createDocTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE documents ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE doc_chunks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "doc_id INTEGER NOT NULL, "
                + "chunk_index INTEGER NOT NULL, "
                + "content TEXT NOT NULL, "
                + "embedding BLOB)");
    }

    // ---- memories ---------------------------------------------------------

    public long remember(String kind, String content, String tags, float[] embedding) {
        ContentValues values = new ContentValues();
        values.put("kind", kind == null ? "note" : kind);
        values.put("content", content);
        values.put("tags", tags == null ? "" : tags);
        values.put("created_at", System.currentTimeMillis());
        if (embedding != null) {
            values.put("embedding", floatsToBytes(embedding));
        }
        return getWritableDatabase().insert("memories", null, values);
    }

    /** Hybrid semantic + keyword + recency recall over memories. */
    public List<Memory> recall(String query, float[] queryEmbedding, int limit) {
        return recall(query, queryEmbedding, limit, null);
    }

    /** Same, restricted to one memory kind (e.g. "solution"). */
    public List<Memory> recall(String query, float[] queryEmbedding, int limit,
                               String kindFilter) {
        List<Memory> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        String[] terms = tokenize(query);
        long now = System.currentTimeMillis();

        String sql = "SELECT id, kind, content, tags, created_at, embedding FROM memories "
                + (kindFilter == null ? "" : "WHERE kind = ? ")
                + "ORDER BY created_at DESC LIMIT 1000";
        String[] args = kindFilter == null ? null : new String[]{kindFilter};
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                Memory memory = new Memory(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getLong(4));
                double score = hybridScore(
                        memory.content + " " + memory.tags + " " + memory.kind,
                        cursor.getBlob(5), terms, queryEmbedding);
                if (score <= 0) {
                    continue;
                }
                double ageDays = (now - memory.createdAt) / 86_400_000.0;
                score += 0.05 / (1.0 + ageDays); // small recency tiebreaker
                insertRanked(scored, scores, memory, score);
            }
        }
        List<Memory> top = new ArrayList<>(scored.subList(0, Math.min(limit, scored.size())));
        for (Memory memory : top) {
            getWritableDatabase().execSQL(
                    "UPDATE memories SET use_count = use_count + 1 WHERE id = ?",
                    new Object[]{memory.id});
        }
        return top;
    }

    // ---- RAG document corpus ----------------------------------------------

    /** Replaces any existing document with the same title. Returns doc id. */
    public long createDocument(String title) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT id FROM documents WHERE title = ?", new String[]{title})) {
            while (cursor.moveToNext()) {
                long oldId = cursor.getLong(0);
                db.execSQL("DELETE FROM doc_chunks WHERE doc_id = ?", new Object[]{oldId});
                db.execSQL("DELETE FROM documents WHERE id = ?", new Object[]{oldId});
            }
        }
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("created_at", System.currentTimeMillis());
        return db.insert("documents", null, values);
    }

    public void addChunk(long docId, int chunkIndex, String content, float[] embedding) {
        ContentValues values = new ContentValues();
        values.put("doc_id", docId);
        values.put("chunk_index", chunkIndex);
        values.put("content", content);
        if (embedding != null) {
            values.put("embedding", floatsToBytes(embedding));
        }
        getWritableDatabase().insert("doc_chunks", null, values);
    }

    /** Hybrid retrieval over every chunk of every indexed document. */
    public List<ChunkHit> searchChunks(String query, float[] queryEmbedding, int limit) {
        List<ChunkHit> scored = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        String[] terms = tokenize(query);

        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT d.title, c.chunk_index, c.content, c.embedding "
                        + "FROM doc_chunks c JOIN documents d ON d.id = c.doc_id", null)) {
            while (cursor.moveToNext()) {
                double score = hybridScore(cursor.getString(2), cursor.getBlob(3),
                        terms, queryEmbedding);
                if (score <= 0) {
                    continue;
                }
                ChunkHit hit = new ChunkHit(cursor.getString(0), cursor.getInt(1),
                        cursor.getString(2), score);
                insertRanked(scored, scores, hit, score);
            }
        }
        return new ArrayList<>(scored.subList(0, Math.min(limit, scored.size())));
    }

    public String listDocuments() {
        StringBuilder sb = new StringBuilder();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT d.title, COUNT(c.id), "
                        + "SUM(CASE WHEN c.embedding IS NULL THEN 0 ELSE 1 END) "
                        + "FROM documents d LEFT JOIN doc_chunks c ON c.doc_id = d.id "
                        + "GROUP BY d.id ORDER BY d.created_at DESC", null)) {
            while (cursor.moveToNext()) {
                sb.append(cursor.getString(0)).append(" — ")
                        .append(cursor.getInt(1)).append(" chunks, ")
                        .append(cursor.getInt(2)).append(" embedded\n");
            }
        }
        return sb.length() == 0 ? "No documents indexed yet." : sb.toString();
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

    /** Learned win rate pulled toward 0.5 when there is little data. */
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

    // ---- scoring & vector helpers -----------------------------------------

    /**
     * Blend of cosine similarity (when both vectors exist) and normalized
     * keyword overlap. Returns 0 when there is no evidence of relevance.
     */
    private static double hybridScore(String text, byte[] embeddingBlob,
                                      String[] terms, float[] queryEmbedding) {
        double keyword = 0;
        if (terms.length > 0) {
            String haystack = text.toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String term : terms) {
                if (term.length() > 2 && haystack.contains(term)) {
                    hits++;
                }
            }
            keyword = (double) hits / terms.length;
        }
        if (queryEmbedding != null && embeddingBlob != null) {
            double cosine = cosine(queryEmbedding, bytesToFloats(embeddingBlob));
            double blended = VECTOR_WEIGHT * cosine + KEYWORD_WEIGHT * keyword;
            return blended > 0.2 ? blended : 0; // similarity floor
        }
        return keyword;
    }

    private static <T> void insertRanked(List<T> items, List<Double> scores,
                                         T item, double score) {
        int insertAt = items.size();
        for (int i = 0; i < scores.size(); i++) {
            if (score > scores.get(i)) {
                insertAt = i;
                break;
            }
        }
        items.add(insertAt, item);
        scores.add(insertAt, score);
    }

    private static String[] tokenize(String query) {
        return query == null ? new String[0]
                : query.toLowerCase(Locale.ROOT).split("\\W+");
    }

    public static byte[] floatsToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
