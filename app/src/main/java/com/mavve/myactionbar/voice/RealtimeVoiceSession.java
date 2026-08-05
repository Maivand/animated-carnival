package com.mavve.myactionbar.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Realtime speech-to-speech voice loop over the OpenAI Realtime API (GA) —
 * the "like Claude voice mode" engine, tuned to work full-duplex on a phone
 * without echo or feedback.
 *
 * Echo control (the hard part of phone voice) is done the professional way,
 * with the platform, not by muting the mic:
 *  - capture from VOICE_COMMUNICATION with the hardware AcousticEchoCanceler,
 *    NoiseSuppressor and AutomaticGainControl attached to the record session;
 *  - the whole session runs in MODE_IN_COMMUNICATION with playback on the
 *    voice-call stream, so the AEC has the speaker signal as its reference;
 *  - playback is decoupled onto its own thread draining a queue, so decoding
 *    and the WebSocket never stall the audio (a common source of stutter),
 *    and barge-in just clears the queue for an instant stop.
 *
 * Audio is 24 kHz mono PCM16 both ways (the Realtime API's format).
 */
public class RealtimeVoiceSession {

    public interface Host {
        String executeTool(String name, JSONObject input);

        void onStatus(String status);

        void onAssistantTranscript(String textDelta);

        void onError(String message);

        /** Diagnostic log line (session events, tool calls, what was heard). */
        void onLog(String line);
    }

    private static final String WS_URL_BASE = "wss://api.openai.com/v1/realtime?model=";
    private static final int SAMPLE_RATE = 24000;
    private static final int CAPTURE_CHUNK = 1200; // 50ms of samples

    private final Context appContext;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String instructions;
    private final JSONArray tools;
    private final Host host;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final ExecutorService toolPool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();

    private WebSocket webSocket;
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread captureThread;
    private Thread playThread;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;

    private AudioManager audioManager;
    private int previousAudioMode;
    private boolean previousSpeakerphone;

    public RealtimeVoiceSession(Context context, String apiKey, String model, String voice,
                                String instructions, JSONArray tools, Host host) {
        this.appContext = context.getApplicationContext();
        this.apiKey = apiKey;
        this.model = (model == null || model.isEmpty()) ? "gpt-realtime" : model;
        // "ash" is the most composed/measured GA voice — a good butler base.
        this.voice = (voice == null || voice.isEmpty()) ? "ash" : voice;
        this.instructions = instructions;
        this.tools = tools;
        this.host = host;
    }

    // ---- lifecycle --------------------------------------------------------

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        enterCommunicationMode();
        Request request = new Request.Builder()
                .url(WS_URL_BASE + model)
                .addHeader("Authorization", "Bearer " + apiKey)
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
        restoreAudioMode();
        if (webSocket != null) {
            webSocket.close(1000, "user ended");
            webSocket = null;
        }
        host.onStatus("Voice session ended.");
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---- audio routing (echo control) -------------------------------------

    private void enterCommunicationMode() {
        try {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                previousAudioMode = audioManager.getMode();
                previousSpeakerphone = audioManager.isSpeakerphoneOn();
                // Communication mode wires in the platform echo canceller and
                // gives the AEC a proper playback reference signal.
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreAudioMode() {
        try {
            if (audioManager != null) {
                audioManager.setSpeakerphoneOn(previousSpeakerphone);
                audioManager.setMode(previousAudioMode);
            }
        } catch (Exception ignored) {
        }
    }

    // ---- websocket --------------------------------------------------------

    private final WebSocketListener listener = new WebSocketListener() {
        @Override
        public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
            configureSession(ws);
            startPlayback();
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
            restoreAudioMode();
        }

        @Override
        public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
            running.set(false);
            stopCapture();
            stopPlayback();
            restoreAudioMode();
        }
    };

    private void configureSession(WebSocket ws) {
        try {
            JSONObject pcm = new JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE);
            JSONObject inputAudio = new JSONObject()
                    .put("format", pcm)
                    .put("transcription", new JSONObject().put("model", "whisper-1"))
                    .put("turn_detection", new JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", 0.5)
                            .put("prefix_padding_ms", 300)
                            .put("silence_duration_ms", 500)
                            .put("interrupt_response", true));
            JSONObject outputAudio = new JSONObject()
                    .put("format", new JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                    .put("voice", voice);
            JSONObject session = new JSONObject()
                    .put("type", "realtime")
                    .put("model", model)
                    .put("instructions", instructions)
                    .put("output_modalities", new JSONArray().put("audio"))
                    .put("audio", new JSONObject().put("input", inputAudio).put("output", outputAudio));
            if (tools != null && tools.length() > 0) {
                session.put("tools", tools);
            }
            ws.send(new JSONObject()
                    .put("type", "session.update")
                    .put("session", session)
                    .toString());
            // Open in character so the butler persona lands immediately.
            ws.send(new JSONObject()
                    .put("type", "response.create")
                    .put("response", new JSONObject().put("instructions",
                            "Greet the user in one short, characterful line as Jarvis, "
                                    + "their composed English butler, and offer your help."))
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
                case "response.output_audio.delta":
                case "response.audio.delta": // pre-GA fallback
                    enqueueAudio(event.optString("delta"));
                    break;
                case "response.output_audio_transcript.delta":
                case "response.audio_transcript.delta":
                    host.onAssistantTranscript(event.optString("delta"));
                    break;
                case "input_audio_buffer.speech_started":
                    // Barge-in: user is talking, drop queued assistant audio now.
                    bargeIn();
                    host.onStatus("Listening…");
                    break;
                case "conversation.item.input_audio_transcription.completed":
                    host.onLog("heard you: " + event.optString("transcript").trim());
                    break;
                case "response.function_call_arguments.done":
                    host.onLog("model → tool " + event.optString("name")
                            + " " + event.optString("arguments"));
                    dispatchToolCall(event);
                    break;
                case "session.created":
                    host.onLog("session.created");
                    break;
                case "session.updated":
                    host.onLog("session.updated (config accepted)");
                    break;
                case "response.done":
                    host.onLog("response.done");
                    break;
                case "error":
                    host.onError("Realtime error: " + event.optJSONObject("error"));
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
        JSONObject parsed;
        try {
            parsed = new JSONObject(event.optString("arguments", "{}"));
        } catch (Exception e) {
            parsed = new JSONObject();
        }
        final JSONObject args = parsed;
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
        enableAudioEffects(recorder.getAudioSessionId());
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

    /** Attach the platform echo canceller, noise suppressor and AGC if present. */
    private void enableAudioEffects(int sessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId);
                if (gainControl != null) {
                    gainControl.setEnabled(true);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopCapture() {
        Thread thread = captureThread;
        captureThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        releaseEffect(echoCanceler);
        releaseEffect(noiseSuppressor);
        releaseEffect(gainControl);
        echoCanceler = null;
        noiseSuppressor = null;
        gainControl = null;
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

    private static void releaseEffect(android.media.audiofx.AudioEffect effect) {
        if (effect != null) {
            try {
                effect.setEnabled(false);
                effect.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- speaker playback (decoupled thread + queue) ----------------------

    private void startPlayback() {
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // STREAM_VOICE_CALL + MODE_IN_COMMUNICATION ties playback into the AEC
        // reference path. Legacy constructor keeps minSdk 21.
        player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, SAMPLE_RATE), AudioTrack.MODE_STREAM);
        player.play();
        playQueue.clear();
        playThread = new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] pcm = playQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (pcm != null && player != null) {
                        player.write(pcm, 0, pcm.length);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {
                }
            }
        }, "jarvis-speaker");
        playThread.start();
    }

    private void enqueueAudio(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return;
        }
        try {
            playQueue.offer(Base64.decode(b64, Base64.NO_WRAP));
        } catch (Exception ignored) {
        }
    }

    /** Instant stop of assistant audio when the user talks over it. */
    private void bargeIn() {
        playQueue.clear();
        if (player != null) {
            try {
                player.pause();
                player.flush();
                player.play();
            } catch (Exception ignored) {
            }
        }
    }

    private void stopPlayback() {
        playQueue.clear();
        Thread thread = playThread;
        playThread = null;
        if (thread != null) {
            thread.interrupt();
        }
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
