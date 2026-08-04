package com.mavve.myactionbar.memory;

import android.content.Context;

import java.util.List;

/**
 * The RAG layer: one facade over EmbeddingClient + ContextDatabase that every
 * agent talks to. Embeds on write, embeds the query on read, and silently
 * degrades to keyword retrieval when no embedding provider is configured —
 * so the second brain always answers, just with sharper recall when vectors
 * are available.
 *
 * All methods are blocking; call from a background thread (agents already
 * run on one).
 */
public class SecondBrain {

    /** Embed at most this many chunks per document to bound cost and time. */
    private static final int MAX_EMBEDDED_CHUNKS = 300;

    private final ContextDatabase db;
    private final EmbeddingClient embeddings;

    public SecondBrain(Context context, ContextDatabase db) {
        this.db = db;
        this.embeddings = new EmbeddingClient(context);
    }

    public boolean semanticSearchAvailable() {
        return embeddings.isConfigured();
    }

    // ---- memories ---------------------------------------------------------

    public void remember(String kind, String content, String tags) {
        db.remember(kind, content, tags, tryEmbed(content));
    }

    public List<ContextDatabase.Memory> recall(String query, int limit) {
        return db.recall(query, tryEmbed(query), limit);
    }

    // ---- documents --------------------------------------------------------

    /**
     * Chunk-index a document into the RAG corpus. Re-indexing the same title
     * replaces the old copy. Returns a summary line for the console.
     */
    public String indexDocument(String title, List<String> chunks) {
        long docId = db.createDocument(title);
        int embedded = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = null;
            if (embedded < MAX_EMBEDDED_CHUNKS) {
                vector = tryEmbed(chunks.get(i));
                if (vector != null) {
                    embedded++;
                }
            }
            db.addChunk(docId, i, chunks.get(i), vector);
        }
        return "Indexed \"" + title + "\": " + chunks.size() + " chunks, "
                + embedded + " embedded"
                + (embeddings.isConfigured() ? "" : " (no embedding provider; keyword-only)");
    }

    public List<ContextDatabase.ChunkHit> searchDocuments(String query, int limit) {
        return db.searchChunks(query, tryEmbed(query), limit);
    }

    public String listDocuments() {
        return db.listDocuments();
    }

    // ---- internals --------------------------------------------------------

    private float[] tryEmbed(String text) {
        if (!embeddings.isConfigured() || text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return embeddings.embed(text);
        } catch (Exception e) {
            return null; // degrade to keyword scoring for this item
        }
    }
}
