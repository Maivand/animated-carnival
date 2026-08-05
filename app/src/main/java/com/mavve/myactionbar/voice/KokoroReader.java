package com.mavve.myactionbar.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads a long document aloud using the self-hosted Kokoro TTS service — the
 * cheap voice for long content. Jarvis's short spoken turns stay on the
 * realtime model; when the user asks for a document to be read, the agent
 * delegates here so narration costs nothing per minute.
 *
 * It fetches the document a few chunks at a time, synthesizes each segment via
 * the Kokoro server, and streams the PCM to an AudioTrack on the voice-call
 * stream (so the same echo-canceller path as the realtime voice applies).
 * While it is speaking it flags the realtime session (onSpeaking) so that
 * session gates its microphone and does not hear — or answer — the narration.
 */
public class KokoroReader {

    public interface Listener {
        void onSpeaking(boolean speaking);

        void onLog(String line);
    }

    private static final int SAMPLE_RATE = 24000;
    private static final int BATCH_CHUNKS = 4;

    private final String url;
    private final String token;
    private final String voice;
    private final Listener listener;

    private List<String> chunks;
    private int cursor;
    private volatile boolean running;
    private volatile boolean paused;
    private AudioTrack player;
    private Thread thread;

    public KokoroReader(String url, String token, String voice, Listener listener) {
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
        this.voice = (voice == null || voice.trim().isEmpty()) ? "bm_george" : voice.trim();
        this.listener = listener;
    }

    public boolean isConfigured() {
        return !url.isEmpty();
    }

    public boolean isReading() {
        return running;
    }

    public synchronized void start(List<String> documentChunks, int fromCursor) {
        stop();
        this.chunks = documentChunks;
        this.cursor = Math.max(0, fromCursor);
        if (chunks == null || chunks.isEmpty()) {
            listener.onLog("kokoro: no document loaded");
            return;
        }
        running = true;
        paused = false;
        openPlayer();
        thread = new Thread(this::loop, "kokoro-reader");
        thread.start();
    }

    public void pause() {
        paused = true;
        listener.onSpeaking(false);
    }

    public void resume() {
        paused = false;
    }

    public synchronized void rewind() {
        cursor = Math.max(0, cursor - 2 * BATCH_CHUNKS);
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
        closePlayer();
        listener.onSpeaking(false);
    }

    // ---- internals --------------------------------------------------------

    private void loop() {
        try {
            while (running && cursor < chunks.size()) {
                if (paused) {
                    Thread.sleep(150);
                    continue;
                }
                int end = Math.min(chunks.size(), cursor + BATCH_CHUNKS);
                StringBuilder sb = new StringBuilder();
                for (int i = cursor; i < end; i++) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(chunks.get(i));
                }
                byte[] pcm = synth(sb.toString());
                if (pcm == null) {
                    listener.onLog("kokoro: synth failed, stopping");
                    break;
                }
                cursor = end;
                listener.onSpeaking(true);
                writePcm(pcm);
                listener.onSpeaking(false);
            }
            if (running && cursor >= chunks.size()) {
                listener.onLog("kokoro: finished reading");
            }
        } catch (InterruptedException ignored) {
            // stopped
        } finally {
            running = false;
            listener.onSpeaking(false);
        }
    }

    private byte[] synth(String text) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json");
            if (!token.isEmpty()) {
                connection.setRequestProperty("X-Kokoro-Token", token);
            }
            byte[] body = new JSONObject()
                    .put("text", text)
                    .put("voice", voice)
                    .toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                listener.onLog("kokoro: HTTP " + code);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buffer.write(tmp, 0, n);
                }
                return buffer.toByteArray();
            }
        } catch (Exception e) {
            listener.onLog("kokoro: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void writePcm(byte[] pcm) {
        AudioTrack p = player;
        if (p == null) {
            return;
        }
        int offset = 0;
        while (running && !paused && offset < pcm.length) {
            int chunk = Math.min(4096, pcm.length - offset);
            int written = p.write(pcm, offset, chunk);
            if (written <= 0) {
                break;
            }
            offset += written;
        }
    }

    private void openPlayer() {
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, SAMPLE_RATE), AudioTrack.MODE_STREAM);
        player.play();
    }

    private void closePlayer() {
        if (player != null) {
            try {
                player.pause();
                player.flush();
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }
}
