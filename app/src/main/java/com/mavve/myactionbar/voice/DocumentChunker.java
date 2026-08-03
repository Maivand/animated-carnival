package com.mavve.myactionbar.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long document into small speakable chunks (roughly sentence-sized).
 * Small chunks are what make "go back 10 seconds" possible: rewinding works by
 * jumping to an earlier chunk, so the shorter the chunks, the finer the seek
 * granularity. They also let a 100-page document stream through the TTS engine
 * one piece at a time, podcast-style, without ever hitting the TTS input limit.
 */
public final class DocumentChunker {

    /** Target maximum characters per chunk. TTS handles ~4000, but short chunks seek better. */
    private static final int MAX_CHUNK_CHARS = 280;

    private DocumentChunker() {
    }

    public static List<String> chunk(String document) {
        List<String> chunks = new ArrayList<>();
        if (document == null) {
            return chunks;
        }
        for (String paragraph : document.split("\n\\s*\n")) {
            String trimmed = paragraph.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            chunks.addAll(splitParagraph(trimmed));
        }
        return chunks;
    }

    private static List<String> splitParagraph(String paragraph) {
        List<String> result = new ArrayList<>();
        // Split after sentence-ending punctuation followed by whitespace.
        String[] sentences = paragraph.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() + 1 > MAX_CHUNK_CHARS) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (sentence.length() > MAX_CHUNK_CHARS) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                result.addAll(splitLongSentence(sentence));
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /** A sentence longer than the limit is cut at word boundaries. */
    private static List<String> splitLongSentence(String sentence) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split(" ")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > MAX_CHUNK_CHARS) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }
}
