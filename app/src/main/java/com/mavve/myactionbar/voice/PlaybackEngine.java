package com.mavve.myactionbar.voice;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a chunked document aloud with TextToSpeech and keeps a timed history of
 * everything it has spoken. The history is what powers the playback tools:
 *
 *  - rewind/forward N seconds  -> walk the history durations to find the chunk
 *                                 that was playing N seconds ago and jump there
 *  - "clarify what you said 30 seconds back" -> transcriptWindow() returns just
 *                                 that slice of text, so the model only reads a
 *                                 few sentences instead of the whole document
 *
 * The document itself is spoken from local text, chunk after chunk, so a long
 * research document plays like a podcast without the model generating (or being
 * billed for) a single token of it.
 */
public class PlaybackEngine {

    public interface Listener {
        void onChunkStarted(int index, int total, String text);

        void onPlaybackFinished();

        void onStatus(String status);
    }

    private static class SpokenRecord {
        final int chunkIndex;
        final String text;
        final long startMs;
        long endMs;

        SpokenRecord(int chunkIndex, String text, long startMs) {
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.startMs = startMs;
        }

        long durationMs() {
            long end = endMs > 0 ? endMs : System.currentTimeMillis();
            return Math.max(0, end - startMs);
        }
    }

    private static final String CHUNK_PREFIX = "chunk-";
    private static final String REPLY_PREFIX = "reply-";
    /** Rough speaking speed used to estimate durations before any history exists. */
    private static final double DEFAULT_CHARS_PER_SECOND = 14.0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private List<String> chunks = new ArrayList<>();
    private int nextIndex = 0;
    private boolean playing = false;
    private float speechRate = 1.0f;
    private final List<SpokenRecord> history = new ArrayList<>();
    private Runnable pendingReplyDone;

    public PlaybackEngine(Context context, Listener listener) {
        this.listener = listener;
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(progressListener);
                notifyStatus("Ready");
            } else {
                notifyStatus("Text-to-speech failed to initialize");
            }
        });
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            if (utteranceId != null && utteranceId.startsWith(CHUNK_PREFIX)) {
                int index = Integer.parseInt(utteranceId.substring(CHUNK_PREFIX.length()));
                final int total;
                final String text;
                synchronized (PlaybackEngine.this) {
                    if (index >= chunks.size()) {
                        return; // a new document was loaded mid-utterance
                    }
                    total = chunks.size();
                    text = chunks.get(index);
                    history.add(new SpokenRecord(index, text, System.currentTimeMillis()));
                }
                mainHandler.post(() -> listener.onChunkStarted(index, total, text));
            }
        }

        @Override
        public void onDone(String utteranceId) {
            if (utteranceId == null) {
                return;
            }
            if (utteranceId.startsWith(CHUNK_PREFIX)) {
                boolean finished = false;
                synchronized (PlaybackEngine.this) {
                    closeLastRecord();
                    if (playing) {
                        nextIndex++;
                        if (nextIndex >= chunks.size()) {
                            playing = false;
                            finished = true;
                        } else {
                            speakCurrentChunkLocked();
                        }
                    }
                }
                if (finished) {
                    mainHandler.post(listener::onPlaybackFinished);
                }
            } else if (utteranceId.startsWith(REPLY_PREFIX)) {
                Runnable done;
                synchronized (PlaybackEngine.this) {
                    done = pendingReplyDone;
                    pendingReplyDone = null;
                }
                if (done != null) {
                    mainHandler.post(done);
                }
            }
        }

        @Override
        public void onError(String utteranceId) {
            onDone(utteranceId);
        }
    };

    // ---- document control -------------------------------------------------

    public synchronized void loadDocument(String text) {
        stopSpeaking();
        chunks = DocumentChunker.chunk(text);
        nextIndex = 0;
        history.clear();
        notifyStatus("Loaded " + chunks.size() + " chunks");
    }

    public synchronized boolean hasDocument() {
        return !chunks.isEmpty();
    }

    /** Start (or restart) reading the whole document from the beginning. */
    public synchronized void readFromBeginning() {
        if (chunks.isEmpty()) {
            notifyStatus("No document loaded");
            return;
        }
        stopSpeaking();
        nextIndex = 0;
        playing = true;
        speakCurrentChunkLocked();
    }

    /** Resume reading from the current position. */
    public synchronized void resume() {
        if (chunks.isEmpty()) {
            notifyStatus("No document loaded");
            return;
        }
        if (playing) {
            return;
        }
        if (nextIndex >= chunks.size()) {
            nextIndex = 0;
        }
        playing = true;
        speakCurrentChunkLocked();
    }

    public synchronized void pause() {
        playing = false;
        stopSpeaking();
        notifyStatus("Paused");
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    /** Jump back roughly the given number of seconds of spoken audio. */
    public synchronized void rewindSeconds(double seconds) {
        if (chunks.isEmpty()) {
            return;
        }
        boolean wasPlaying = playing;
        stopSpeaking();
        closeLastRecord();

        long remainingMs = (long) (seconds * 1000);
        int targetChunk = currentChunkIndex();
        for (int i = history.size() - 1; i >= 0 && remainingMs > 0; i--) {
            SpokenRecord record = history.get(i);
            remainingMs -= record.durationMs();
            targetChunk = record.chunkIndex;
        }
        nextIndex = Math.max(0, targetChunk);
        playing = true;
        speakCurrentChunkLocked();
        if (!wasPlaying) {
            notifyStatus("Rewound and resumed");
        }
    }

    /** Skip forward roughly the given number of seconds of spoken audio. */
    public synchronized void forwardSeconds(double seconds) {
        if (chunks.isEmpty()) {
            return;
        }
        stopSpeaking();
        closeLastRecord();

        double avgChunkSeconds = averageChunkSeconds();
        int skip = Math.max(1, (int) Math.round(seconds / avgChunkSeconds));
        nextIndex = Math.min(chunks.size() - 1, currentChunkIndex() + skip);
        playing = true;
        speakCurrentChunkLocked();
    }

    public synchronized void jumpToChunk(int index) {
        if (chunks.isEmpty()) {
            return;
        }
        stopSpeaking();
        nextIndex = Math.max(0, Math.min(chunks.size() - 1, index));
        playing = true;
        speakCurrentChunkLocked();
    }

    public synchronized void setSpeechRate(float rate) {
        speechRate = Math.max(0.5f, Math.min(2.0f, rate));
        if (ttsReady) {
            tts.setSpeechRate(speechRate);
        }
        notifyStatus(String.format(Locale.US, "Speed %.1fx", speechRate));
    }

    public synchronized float getSpeechRate() {
        return speechRate;
    }

    // ---- information for the agent ---------------------------------------

    /** Text that was spoken during roughly the last N seconds of playback. */
    public synchronized String transcriptWindow(double secondsBack) {
        long remainingMs = (long) (secondsBack * 1000);
        List<String> parts = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && remainingMs > 0; i--) {
            SpokenRecord record = history.get(i);
            parts.add(0, record.text);
            remainingMs -= record.durationMs();
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    public synchronized String statusDescription() {
        if (chunks.isEmpty()) {
            return "No document loaded.";
        }
        int current = currentChunkIndex();
        int percent = (int) (100.0 * current / Math.max(1, chunks.size()));
        return "Chunk " + (current + 1) + " of " + chunks.size() + " (" + percent + "%), "
                + (playing ? "playing" : "paused") + ", speed "
                + String.format(Locale.US, "%.1fx", speechRate) + ".";
    }

    /** First sentence of each paragraph-ish region, so the agent can jump around. */
    public synchronized String outline() {
        if (chunks.isEmpty()) {
            return "No document loaded.";
        }
        StringBuilder sb = new StringBuilder();
        int step = Math.max(1, chunks.size() / 20);
        for (int i = 0; i < chunks.size(); i += step) {
            String chunk = chunks.get(i);
            sb.append("chunk ").append(i).append(": ")
                    .append(chunk, 0, Math.min(80, chunk.length())).append('\n');
        }
        return sb.toString();
    }

    // ---- speaking assistant replies ---------------------------------------

    /**
     * Speak a short assistant reply. Document playback is paused while the
     * assistant talks and resumed afterwards if it was playing.
     */
    public synchronized void speakReply(String text, Runnable onDone) {
        if (!ttsReady || text == null || text.trim().isEmpty()) {
            if (onDone != null) {
                mainHandler.post(onDone);
            }
            return;
        }
        final boolean wasPlaying = playing;
        playing = false;
        stopSpeaking();
        closeLastRecord();
        pendingReplyDone = () -> {
            if (wasPlaying) {
                resume();
            }
            if (onDone != null) {
                onDone.run();
            }
        };
        Bundle params = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, REPLY_PREFIX + System.nanoTime());
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    // ---- internals --------------------------------------------------------

    private void speakCurrentChunkLocked() {
        if (!ttsReady) {
            notifyStatus("Text-to-speech not ready yet");
            playing = false;
            return;
        }
        String chunk = chunks.get(nextIndex);
        Bundle params = new Bundle();
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, CHUNK_PREFIX + nextIndex);
    }

    private void stopSpeaking() {
        if (ttsReady) {
            tts.stop();
        }
    }

    private void closeLastRecord() {
        if (!history.isEmpty()) {
            SpokenRecord last = history.get(history.size() - 1);
            if (last.endMs == 0) {
                last.endMs = System.currentTimeMillis();
            }
        }
    }

    private int currentChunkIndex() {
        if (!history.isEmpty()) {
            return history.get(history.size() - 1).chunkIndex;
        }
        return Math.min(nextIndex, Math.max(0, chunks.size() - 1));
    }

    private double averageChunkSeconds() {
        long totalMs = 0;
        int counted = 0;
        for (SpokenRecord record : history) {
            if (record.endMs > 0) {
                totalMs += record.durationMs();
                counted++;
            }
        }
        if (counted > 0) {
            return Math.max(0.5, totalMs / 1000.0 / counted);
        }
        int avgChars = 0;
        for (String chunk : chunks) {
            avgChars += chunk.length();
        }
        avgChars = chunks.isEmpty() ? 100 : avgChars / chunks.size();
        return Math.max(0.5, avgChars / (DEFAULT_CHARS_PER_SECOND * speechRate));
    }

    private void notifyStatus(String status) {
        mainHandler.post(() -> listener.onStatus(status));
    }
}
