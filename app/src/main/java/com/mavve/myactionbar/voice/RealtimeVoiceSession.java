package com.mavve.myactionbar.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Realtime speech-to-speech voice loop over the OpenAI Realtime API — the
 * "like Claude voice mode" engine. Streams microphone PCM to the model and
 * streams the model's voice back, with server-side voice-activity detection
 * for natural turn-taking and barge-in (talk over Jarvis and it stops).
 *
 * The model is not the whole brain: it is the ears, mouth, and quick
 * conversation. Real work is exposed as function tools it can call —
 * instant on-device playback control, plus a single ask_jarvis_agent tool
 * that runs the full Jarvis agent stack (model router, second brain, RAG,
 * sub-agents, delegation) and returns text for the voice to speak. So we get
 * Claude-quality voice without throwing away anything we built.
 *
 * Audio is 24 kHz mono PCM16 in both directions (the Realtime API's format).
 */
public class RealtimeVoiceSession {

    public interface Host {
        /** Execute a tool by name; returns a result string. May block. */
        String executeTool(String name, JSONObject input);

        void onStatus(String status);

        /** Streamed assistant transcript (for on-screen display). */
        void onAssistantTranscript(String textDelta);

        void onError(String message);
    }

    private static final String WS_URL_BASE = "wss://api.openai.com/v1/realtime?model=";
    private static final int SAMPLE_RATE = 24000;
    private static final int CAPTURE_CHUNK = 2400; // 100ms of samples

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String instructions;
    private final JSONArray tools;
    private final Host host;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final ExecutorService toolPool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private WebSocket webSocket;
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread captureThread;

    public RealtimeVoiceSession(String apiKey, String model, String voice,
                                String instructions, JSONArray tools, Host host) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isEmpty()) ? "gpt-realtime" : model;
        this.voice = (voice == null || voice.isEmpty()) ? "marin" : voice;
        this.instructions = instructions;
        this.tools = tools;
        this.host = host;
    }

    // ---- lifecycle --------------------------------------------------------

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        Request request = new Request.Builder()
                .url(WS_URL_BASE + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();
        webSocket = client.newWebSocket(request, listener);
        host.onStatus("Connecting…");
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        stopCapture();
        stopPlayback();
        if (webSocket != null) {
            webSocket.close(1000, "user ended");
            webSocket = null;
        }
        host.onStatus("Voice session ended.");
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---- websocket --------------------------------------------------------

    private final WebSocketListener listener = new WebSocketListener() {
        @Override
        public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
            configureSession(ws);
            startCapture();
            host.onStatus("Listening — just talk.");
        }

        @Override
        public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
            handleEvent(text);
        }

        @Override
        public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
            if (running.get()) {
                host.onError("Voice connection failed: " + t.getMessage());
            }
            running.set(false);
            stopCapture();
            stopPlayback();
        }

        @Override
        public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
            running.set(false);
            stopCapture();
            stopPlayback();
        }
    };

    private void configureSession(WebSocket ws) {
        try {
            JSONObject session = new JSONObject()
                    .put("modalities", new JSONArray().put("audio").put("text"))
                    .put("instructions", instructions)
                    .put("voice", voice)
                    .put("input_audio_format", "pcm16")
                    .put("output_audio_format", "pcm16")
                    .put("input_audio_transcription",
                            new JSONObject().put("model", "whisper-1"))
                    .put("turn_detection", new JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", 0.5)
                            .put("silence_duration_ms", 500))
                    .put("tool_choice", "auto");
            if (tools != null && tools.length() > 0) {
                session.put("tools", tools);
            }
            ws.send(new JSONObject()
                    .put("type", "session.update")
                    .put("session", session)
                    .toString());
        } catch (Exception e) {
            host.onError("Session config failed: " + e.getMessage());
        }
    }

    private void handleEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type");
            switch (type) {
                case "response.audio.delta":
                    playAudioBase64(event.optString("delta"));
                    break;
                case "response.audio_transcript.delta":
                    host.onAssistantTranscript(event.optString("delta"));
                    break;
                case "input_audio_buffer.speech_started":
                    // Barge-in: user started talking, so stop Jarvis mid-sentence.
                    flushPlayback();
                    host.onStatus("Listening…");
                    break;
                case "response.function_call_arguments.done":
                    dispatchToolCall(event);
                    break;
                case "error":
                    host.onError("Realtime error: "
                            + event.optJSONObject("error"));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            host.onError("Event parse error: " + e.getMessage());
        }
    }

    private void dispatchToolCall(final JSONObject event) {
        final String callId = event.optString("call_id");
        final String name = event.optString("name");
        JSONObject input;
        try {
            input = new JSONObject(event.optString("arguments", "{}"));
        } catch (Exception e) {
            input = new JSONObject();
        }
        final JSONObject args = input;
        toolPool.submit(() -> {
            String result;
            try {
                result = host.executeTool(name, args);
            } catch (Exception e) {
                result = "Tool error: " + e.getMessage();
            }
            sendToolResult(callId, result);
        });
    }

    private void sendToolResult(String callId, String result) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        try {
            ws.send(new JSONObject()
                    .put("type", "conversation.item.create")
                    .put("item", new JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", result == null ? "" : result))
                    .toString());
            ws.send(new JSONObject().put("type", "response.create").toString());
        } catch (Exception e) {
            host.onError("Tool result send failed: " + e.getMessage());
        }
    }

    // ---- microphone capture ----------------------------------------------

    private void startCapture() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, CAPTURE_CHUNK * 2 * 4);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (Exception e) {
            host.onError("Microphone init failed (permission?): " + e.getMessage());
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            host.onError("Microphone unavailable. Grant the mic permission and retry.");
            return;
        }
        recorder.startRecording();
        captureThread = new Thread(() -> {
            byte[] buffer = new byte[CAPTURE_CHUNK * 2];
            while (running.get()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    String b64 = Base64.encodeToString(
                            read == buffer.length ? buffer : trim(buffer, read),
                            Base64.NO_WRAP);
                    WebSocket ws = webSocket;
                    if (ws != null) {
                        try {
                            ws.send(new JSONObject()
                                    .put("type", "input_audio_buffer.append")
                                    .put("audio", b64).toString());
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "jarvis-mic");
        captureThread.start();
    }

    private void stopCapture() {
        Thread thread = captureThread;
        captureThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        if (recorder != null) {
            try {
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.stop();
                }
            } catch (Exception ignored) {
            }
            recorder.release();
            recorder = null;
        }
    }

    // ---- speaker playback -------------------------------------------------

    private synchronized void playAudioBase64(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return;
        }
        ensurePlayer();
        byte[] pcm = Base64.decode(b64, Base64.NO_WRAP);
        player.write(pcm, 0, pcm.length);
    }

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // Legacy constructor keeps minSdk 21 (AudioTrack.Builder is API 23+).
        player = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, SAMPLE_RATE), AudioTrack.MODE_STREAM);
        player.play();
    }

    /** Barge-in: drop whatever Jarvis was about to say. */
    private synchronized void flushPlayback() {
        if (player != null) {
            try {
                player.pause();
                player.flush();
                player.play();
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void stopPlayback() {
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

    private static byte[] trim(byte[] source, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, 0, out, 0, length);
        return out;
    }
}
