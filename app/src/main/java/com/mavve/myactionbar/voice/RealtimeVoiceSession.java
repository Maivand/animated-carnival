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

    // Adaptive noise floor so ambient/random sounds don't cut Jarvis off.
    private volatile double noiseFloorNorm = -1;   // tracked ambient level (0..1)
    private volatile double lastRmsNorm = 0;       // energy of the latest chunk
    private volatile double vadThreshold = 0.5;    // current server VAD threshold
    private volatile boolean assistantSpeaking = false; // Jarvis is talking now
    private volatile boolean externalSpeaking = false;  // Kokoro is narrating
    private long lastThresholdUpdateMs = 0;
    private static final double SPEECH_FACTOR = 3.0;   // × floor to count as speech
    private static final double SPEECH_MARGIN = 0.02;  // absolute headroom

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
        // Default to the mini realtime model — ~3x cheaper, same API/voices.
        this.model = (model == null || model.isEmpty()) ? "gpt-realtime-mini" : model;
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

    /** Tell the session that another voice (Kokoro) is speaking, so it gates
     *  the mic the same way it does for its own output. */
    public void setExternalSpeaking(boolean speaking) {
        this.externalSpeaking = speaking;
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
                            "Greet the user, Lord Mavve, in one short characterful line "
                                    + "as Jarvis, their composed English butler, and offer "
                                    + "your help."))
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
                    assistantSpeaking = true;
                    enqueueAudio(event.optString("delta"));
                    break;
                case "response.output_audio.done":
                case "response.done":
                    assistantSpeaking = false;
                    host.onLog("response.done");
                    break;
                case "response.output_audio_transcript.delta":
                case "response.audio_transcript.delta":
                    host.onAssistantTranscript(event.optString("delta"));
                    break;
                case "input_audio_buffer.speech_started":
                    // Only treat it as a real interruption if local mic energy is
                    // clearly above the adaptive noise floor — a stray clatter or
                    // background chatter won't cut Jarvis off.
                    if (isLikelySpeech()) {
                        bargeIn();
                        host.onStatus("Listening…");
                    } else {
                        host.onLog("ignored noise below floor (rms "
                                + fmt(lastRmsNorm) + " < floor " + fmt(noiseFloorNorm) + ")");
                    }
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
                    updateNoiseFloor(buffer, read);
                    // While Jarvis is speaking, only forward mic audio if it's
                    // clearly the user (above the adaptive floor). Otherwise his
                    // own voice, leaking past the echo canceller, would be sent
                    // back and he'd answer himself.
                    if ((assistantSpeaking || externalSpeaking) && !isLikelySpeech()) {
                        continue;
                    }
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

    // ---- adaptive noise floor --------------------------------------------

    /** RMS energy of a chunk, plus floor tracking and adaptive VAD threshold. */
    private void updateNoiseFloor(byte[] buffer, int len) {
        long sumSq = 0;
        int n = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
            sumSq += (long) sample * sample;
            n++;
        }
        if (n == 0) {
            return;
        }
        double rms = Math.sqrt((double) sumSq / n) / 32768.0;
        lastRmsNorm = rms;
        boolean loud = noiseFloorNorm >= 0 && rms > noiseFloorNorm * SPEECH_FACTOR + SPEECH_MARGIN;
        if (!loud) {
            // Adapt the floor only during quiet — track the room, ignore speech.
            noiseFloorNorm = noiseFloorNorm < 0 ? rms : 0.97 * noiseFloorNorm + 0.03 * rms;
        }
        maybeAdaptThreshold();
    }

    private boolean isLikelySpeech() {
        return noiseFloorNorm < 0
                || lastRmsNorm > noiseFloorNorm * SPEECH_FACTOR + SPEECH_MARGIN;
    }

    /** Raise/lower the server VAD threshold to match the room, debounced. */
    private void maybeAdaptThreshold() {
        long now = System.currentTimeMillis();
        if (now - lastThresholdUpdateMs < 4000 || noiseFloorNorm < 0) {
            return;
        }
        double desired = clamp(0.35 + 4.0 * noiseFloorNorm, 0.3, 0.85);
        if (Math.abs(desired - vadThreshold) < 0.1) {
            return;
        }
        vadThreshold = desired;
        lastThresholdUpdateMs = now;
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        try {
            JSONObject turn = new JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", desired)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 500)
                    .put("interrupt_response", true);
            JSONObject session = new JSONObject().put("audio",
                    new JSONObject().put("input", new JSONObject().put("turn_detection", turn)));
            ws.send(new JSONObject()
                    .put("type", "session.update")
                    .put("session", session)
                    .toString());
            host.onLog("adaptive floor → VAD threshold " + fmt(desired)
                    + " (noise " + fmt(noiseFloorNorm) + ")");
        } catch (Exception ignored) {
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 1000) / 1000.0);
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
