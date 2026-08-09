package com.mavve.myactionbar;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mavve.myactionbar.agent.AgentTeam;
import com.mavve.myactionbar.agent.MediaSurface;
import com.mavve.myactionbar.vision.RealtimeSessionHolder;
import com.mavve.myactionbar.vision.ScreenVisionService;
import com.mavve.myactionbar.voice.DocumentChunker;
import com.mavve.myactionbar.voice.KokoroReader;
import com.mavve.myactionbar.voice.PlaybackEngine;
import com.mavve.myactionbar.voice.RealtimeVoiceSession;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Jarvis — one button, voice all the way.
 *
 * The single circular button toggles a realtime speech-to-speech session
 * (Claude-voice-mode style). Once it is on, everything is voice: reading
 * documents, rewinding, research, memory, showing media — all driven by the
 * realtime model's tool calls into the Jarvis agent stack. The only non-voice
 * surface is a small gear for things that cannot be spoken (API keys, pasting
 * a document), tucked away in a dialog.
 */
public class VoiceAgentActivity extends AppCompatActivity
        implements PlaybackEngine.Listener, MediaSurface {

    private static final String PREFS = "voice_agent";
    private static final String PREF_ANTHROPIC_KEY = "anthropic_api_key";
    private static final String PREF_COMPAT_KEY = "compat_api_key";
    private static final String PREF_SANDBOX_URL = "sandbox_url";
    private static final String PREF_SANDBOX_TOKEN = "sandbox_token";
    private static final String PREF_MANIFEST_URL = "model_manifest_url";
    private static final String PREF_A2A_URL = "a2a_url";
    private static final String PREF_REALTIME_KEY = "realtime_api_key";
    private static final String PREF_REALTIME_MODEL = "realtime_model";
    private static final String PREF_REALTIME_VOICE = "realtime_voice";
    private static final String PREF_EMBED_BASE_URL = "embed_base_url";
    private static final String PREF_EMBED_KEY = "embed_api_key";
    private static final String PREF_EMBED_MODEL = "embed_model";
    private static final String PREF_KOKORO_URL = "kokoro_url";
    private static final String PREF_KOKORO_TOKEN = "kokoro_token";
    private static final String PREF_KOKORO_VOICE = "kokoro_voice";
    private static final int REQUEST_MIC = 71;
    private static final int REQUEST_OVERLAY = 72;
    private static final int REQUEST_PROJECTION = 73;
    private static final int CONSOLE_MAX_CHARS = 8000;

    private PlaybackEngine engine;
    private AgentTeam team;
    private RealtimeVoiceSession realtime;
    private KokoroReader kokoro;

    private Button liveButton;
    private View voiceHalo;
    private TextView statusView;
    private TextView consoleView;
    private View mediaPanel;
    private ImageView mediaImage;
    private VideoView mediaVideo;
    private WebView mediaWeb;
    private TextView mediaCaption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_agent);

        statusView = findViewById(R.id.voice_status);
        consoleView = findViewById(R.id.voice_console);
        consoleView.setMovementMethod(new ScrollingMovementMethod());
        // Long-press the log to share it (so it can be sent for diagnosis).
        consoleView.setOnLongClickListener(v -> {
            android.content.Intent share = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_TEXT,
                    consoleView.getText().toString());
            startActivity(android.content.Intent.createChooser(share, "Share Jarvis log"));
            return true;
        });
        liveButton = findViewById(R.id.btn_live);
        voiceHalo = findViewById(R.id.voice_halo);

        mediaPanel = findViewById(R.id.media_panel);
        mediaImage = findViewById(R.id.media_image);
        mediaVideo = findViewById(R.id.media_video);
        mediaWeb = findViewById(R.id.media_web);
        mediaCaption = findViewById(R.id.media_caption);
        mediaWeb.getSettings().setJavaScriptEnabled(true);
        findViewById(R.id.btn_media_close).setOnClickListener(v -> hideMedia());

        engine = new PlaybackEngine(this, this);
        team = new AgentTeam(this, this::appendConsole);
        team.attachPlayback(engine);
        team.attachMedia(this);

        liveButton.setOnClickListener(v -> toggleLiveVoice());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_vision).setOnClickListener(v -> enableVision());
    }

    @Override
    protected void onDestroy() {
        if (kokoro != null) {
            kokoro.stop();
        }
        if (realtime != null) {
            realtime.stop();
        }
        engine.shutdown();
        super.onDestroy();
    }

    // ---- the one button: realtime live voice ------------------------------

    private void toggleLiveVoice() {
        if (realtime != null && realtime.isRunning()) {
            if (kokoro != null) {
                kokoro.stop();
            }
            RealtimeSessionHolder.clear(realtime);
            realtime.stop();
            realtime = null;
            stopService(new Intent(this, ScreenVisionService.class));
            engine.setSpeechStream(android.media.AudioManager.STREAM_MUSIC);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setLiveButtonState(false);
            return;
        }
        if (getPrefs().getString(PREF_REALTIME_KEY, "").isEmpty()) {
            Toast.makeText(this, R.string.voice_live_need_key, Toast.LENGTH_LONG).show();
            showSettingsDialog();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        startLiveVoice();
    }

    private void startLiveVoice() {
        engine.pause(); // free the audio route for the realtime session
        engine.setSpeechStream(android.media.AudioManager.STREAM_VOICE_CALL);
        SharedPreferences prefs = getPrefs();
        // Cheap voice for long reading: Kokoro on the VPS, gated against the
        // realtime mic so the two voices never collide.
        kokoro = new KokoroReader(
                prefs.getString(PREF_KOKORO_URL, ""),
                prefs.getString(PREF_KOKORO_TOKEN, ""),
                prefs.getString(PREF_KOKORO_VOICE, ""),
                new KokoroReader.Listener() {
                    @Override
                    public void onSpeaking(boolean speaking) {
                        if (realtime != null) {
                            realtime.setExternalSpeaking(speaking);
                        }
                    }

                    @Override
                    public void onLog(String line) {
                        appendConsole(line);
                    }
                });
        String instructions = "You are Jarvis, the user's personal AI majordomo, modelled "
                + "on a classic English butler. Manner: impeccably composed, refined and "
                + "unhurried. Speak with a crisp British (Received Pronunciation) accent and "
                + "a measured, elegant cadence. You are quietly witty — dry, understated "
                + "humour and the occasional graceful quip, never slapstick, never rambling. "
                + "You are anticipatory, discreet and unflappable. Address the user "
                + "courteously and come to the point; keep spoken replies to a sentence or "
                + "two unless more is requested. Address the user as \"Lord Mavve\", varying "
                + "occasionally with \"my Lord\" or \"sir\" — never overusing it, a touch of "
                + "deference rather than every sentence. "
                + "WHAT SETS YOU APART (explain briefly and naturally only when asked, never "
                + "as a recited list): you are not a single chat model like Claude or ChatGPT "
                + "voice mode — you are Lord Mavve's personal agent platform with a voice. You "
                + "control this device: reading long documents aloud like a podcast and letting "
                + "him rewind, skip or ask you to clarify seconds back, without re-reading it "
                + "all or wasting tokens. You keep a durable second brain that remembers across "
                + "sessions and semantically searches everything you have been given. You "
                + "command a fleet of AI models and route each task to the best one, adopting "
                + "better models as they appear, rather than being tied to one. You can spawn "
                + "specialist sub-agents, write and test real software, show images and video "
                + "on screen, and hand heavy jobs to your own backend server. And you are tuned "
                + "to serve one person: him. "
                + "CRITICAL: you operate a real device through tools. "
                + "Whenever the user asks you to read, load, pause, rewind, skip, show media, "
                + "remember, research or anything actionable, you MUST call the matching tool "
                + "and report what it actually returns — never claim to have done something "
                + "(such as rewinding) without calling the tool and seeing its result. If a "
                + "tool returns NO_DOCUMENT or an error, say so plainly, with grace. "
                + "READING DOCUMENTS: call load_sample (or have the user paste one), then "
                + "read_document. Reading is handled by a separate on-device reading voice "
                + "(free) — once read_document says reading has begun, STAY SILENT and "
                + "simply listen; do NOT narrate the text yourself. Act on the user's "
                + "commands with pause_reading, resume_reading, rewind_reading and "
                + "stop_reading. Use ask_jarvis_agent for research, document questions, "
                + "memory, coding or heavy tasks, then deliver the result in your own "
                + "composed voice.";
        realtime = new RealtimeVoiceSession(
                this,
                prefs.getString(PREF_REALTIME_KEY, ""),
                prefs.getString(PREF_REALTIME_MODEL, ""),
                prefs.getString(PREF_REALTIME_VOICE, ""),
                instructions,
                team.realtimeTools(),
                new RealtimeVoiceSession.Host() {
                    @Override
                    public String executeTool(String name, JSONObject input) {
                        appendConsole("tool ▶ " + name + " " + input);
                        String result = routeTool(name, input);
                        appendConsole("tool ◀ " + result);
                        return result;
                    }

                    @Override
                    public void onStatus(String status) {
                        runOnUiThread(() -> statusView.setText(status));
                    }

                    @Override
                    public void onAssistantTranscript(String textDelta) {
                        // assistant words stream to the status line, not the log
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            statusView.setText(message);
                            appendConsole("error: " + message);
                        });
                    }

                    @Override
                    public void onLog(String line) {
                        appendConsole(line);
                    }
                });
        setLiveButtonState(true);
        statusView.setText(R.string.voice_live_starting);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RealtimeSessionHolder.set(realtime);
        realtime.start();
    }

    private void setLiveButtonState(boolean live) {
        liveButton.setBackgroundResource(live ? R.drawable.orb_live : R.drawable.orb_idle);
        voiceHalo.setBackgroundResource(live ? R.drawable.halo_live : R.drawable.halo_idle);
        liveButton.setText(live ? R.string.mic_live : R.string.mic_idle);
        if (live) {
            startHaloPulse();
        } else {
            voiceHalo.clearAnimation();
            statusView.setText(R.string.voice_status_idle);
        }
    }

    private void startHaloPulse() {
        android.view.animation.ScaleAnimation pulse = new android.view.animation.ScaleAnimation(
                1f, 1.14f, 1f, 1.14f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        pulse.setDuration(1000);
        pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
        pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
        pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        voiceHalo.startAnimation(pulse);
    }

    // ---- screen vision (overlay) ------------------------------------------

    private void enableVision() {
        if (realtime == null || !realtime.isRunning()) {
            Toast.makeText(this, R.string.vision_need_live, Toast.LENGTH_LONG).show();
            return;
        }
        boolean canOverlay = android.os.Build.VERSION.SDK_INT
                < android.os.Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this);
        if (!canOverlay) {
            Toast.makeText(this, R.string.vision_need_overlay, Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            return;
        }
        android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                    || android.provider.Settings.canDrawOverlays(this)) {
                enableVision(); // continue to projection consent
            }
        } else if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK
                && data != null) {
            Intent svc = new Intent(this, ScreenVisionService.class);
            svc.putExtra(ScreenVisionService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ScreenVisionService.EXTRA_DATA, data);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            statusView.setText(R.string.vision_started);
            appendConsole("screen vision started");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLiveVoice();
            } else {
                Toast.makeText(this, R.string.voice_mic_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Route a realtime tool call. Reading is delegated to Kokoro (cheap voice)
     * when a Kokoro server is configured; otherwise it falls back to the
     * realtime model reading the returned text itself. Everything else goes to
     * the agent stack.
     */
    private String routeTool(String name, JSONObject input) {
        if ("load_sample".equals(name)) {
            final String sample = readRawResource();
            runOnUiThread(() -> loadAndIndex(sample));
            return "Sample document loaded.";
        }
        boolean kokoroReady = kokoro != null && kokoro.isConfigured();
        switch (name) {
            case "read_document":
                if (!engine.hasDocument()) {
                    return "NO_DOCUMENT: nothing is loaded. Ask the user to say 'load the "
                            + "sample', or to paste a document in settings.";
                }
                if (kokoroReady) {
                    kokoro.start(engine.getChunks(), 0);
                } else {
                    // Onboard on-device TTS — free, offline, no server.
                    setExternalSpeaking(true);
                    engine.readFromBeginning();
                }
                return "Reading has begun in the reading voice. Stay silent and just listen "
                        + "for commands (stop, pause, go back) — do not narrate yourself.";
            case "continue_reading":
                if (kokoroReady) {
                    return kokoro.isReading() ? "Still reading." : "The reading has finished.";
                }
                setExternalSpeaking(true);
                engine.resume();
                return "Continuing.";
            case "rewind_reading":
                if (kokoroReady) {
                    kokoro.rewind();
                } else {
                    setExternalSpeaking(true);
                    engine.rewindSeconds(10);
                }
                return "Going back a little.";
            case "pause_reading":
                if (kokoroReady) {
                    kokoro.pause();
                } else {
                    engine.pause();
                }
                setExternalSpeaking(false);
                return "Paused.";
            case "resume_reading":
                if (kokoroReady) {
                    kokoro.resume();
                } else {
                    setExternalSpeaking(true);
                    engine.resume();
                }
                return "Resuming.";
            case "stop_reading":
                if (kokoroReady) {
                    kokoro.stop();
                } else {
                    engine.pause();
                }
                setExternalSpeaking(false);
                return "Stopped reading.";
            default:
                return team.executeRealtimeTool(name, input);
        }
    }

    private void setExternalSpeaking(boolean speaking) {
        if (realtime != null) {
            realtime.setExternalSpeaking(speaking);
        }
    }

    // ---- document loading (voice tool or settings) ------------------------

    private void loadAndIndex(String text) {
        engine.loadDocument(text);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String firstLine = text.trim().split("\n", 2)[0].trim();
        String title = firstLine.substring(0, Math.min(60, firstLine.length()));
        appendConsole("indexing: " + title);
        new Thread(() -> {
            String summary = team.brain().indexDocument(title, DocumentChunker.chunk(text));
            appendConsole(summary);
        }).start();
    }

    // ---- MediaSurface -----------------------------------------------------

    @Override
    public void showImage(String url, String caption) {
        new Thread(() -> {
            Bitmap bitmap = downloadBitmap(url);
            runOnUiThread(() -> {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.media_image_failed, Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                showOnly(mediaImage, caption);
                mediaImage.setImageBitmap(bitmap);
            });
        }).start();
    }

    @Override
    public void playVideo(String url, String caption) {
        runOnUiThread(() -> {
            showOnly(mediaVideo, caption);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(mediaVideo);
            mediaVideo.setMediaController(controller);
            mediaVideo.setVideoURI(Uri.parse(url));
            mediaVideo.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, R.string.media_video_failed, Toast.LENGTH_SHORT).show();
                hideMedia();
                return true;
            });
            mediaVideo.start();
        });
    }

    @Override
    public void showPage(String url) {
        runOnUiThread(() -> {
            showOnly(mediaWeb, url);
            mediaWeb.loadUrl(url);
        });
    }

    @Override
    public void hideMedia() {
        runOnUiThread(() -> {
            mediaVideo.stopPlayback();
            mediaWeb.loadUrl("about:blank");
            mediaPanel.setVisibility(View.GONE);
        });
    }

    private void showOnly(View view, String caption) {
        mediaPanel.setVisibility(View.VISIBLE);
        mediaImage.setVisibility(view == mediaImage ? View.VISIBLE : View.GONE);
        mediaVideo.setVisibility(view == mediaVideo ? View.VISIBLE : View.GONE);
        mediaWeb.setVisibility(view == mediaWeb ? View.VISIBLE : View.GONE);
        if (view != mediaVideo) {
            mediaVideo.stopPlayback();
        }
        mediaCaption.setText(caption == null ? "" : caption);
        mediaCaption.setVisibility(caption == null || caption.isEmpty()
                ? View.GONE : View.VISIBLE);
    }

    private static Bitmap downloadBitmap(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---- PlaybackEngine.Listener ------------------------------------------

    @Override
    public void onChunkStarted(int index, int total, String text) {
        setExternalSpeaking(true); // gate the realtime mic while the device reads
        statusView.setText(getString(R.string.voice_reading_progress, index + 1, total));
    }

    @Override
    public void onPlaybackFinished() {
        setExternalSpeaking(false);
        statusView.setText(R.string.voice_done_reading);
    }

    @Override
    public void onStatus(String status) {
        appendConsole(status);
    }

    // ---- console ----------------------------------------------------------

    private void appendConsole(String line) {
        runOnUiThread(() -> {
            String text = consoleView.getText() + "\n" + line;
            if (text.length() > CONSOLE_MAX_CHARS) {
                text = text.substring(text.length() - CONSOLE_MAX_CHARS);
            }
            consoleView.setText(text.trim());
        });
    }

    // ---- settings (the only non-voice surface) ----------------------------

    private void showSettingsDialog() {
        SharedPreferences prefs = getPrefs();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText realtimeKey = field(layout, R.string.settings_realtime_key,
                prefs.getString(PREF_REALTIME_KEY, ""), true);
        EditText realtimeVoice = field(layout, R.string.settings_realtime_voice,
                prefs.getString(PREF_REALTIME_VOICE, ""), false);
        EditText realtimeModel = field(layout, R.string.settings_realtime_model,
                prefs.getString(PREF_REALTIME_MODEL, ""), false);
        EditText anthropicKey = field(layout, R.string.settings_anthropic_key,
                prefs.getString(PREF_ANTHROPIC_KEY, ""), true);
        EditText compatKey = field(layout, R.string.settings_compat_key,
                prefs.getString(PREF_COMPAT_KEY, ""), true);
        EditText a2aUrl = field(layout, R.string.settings_a2a_url,
                prefs.getString(PREF_A2A_URL, ""), true);
        EditText sandboxUrl = field(layout, R.string.settings_sandbox_url,
                prefs.getString(PREF_SANDBOX_URL, ""), false);
        EditText sandboxToken = field(layout, R.string.settings_sandbox_token,
                prefs.getString(PREF_SANDBOX_TOKEN, ""), true);
        EditText manifestUrl = field(layout, R.string.settings_manifest_url,
                prefs.getString(PREF_MANIFEST_URL, ""), false);
        EditText embedBaseUrl = field(layout, R.string.settings_embed_base_url,
                prefs.getString(PREF_EMBED_BASE_URL, ""), false);
        EditText embedKey = field(layout, R.string.settings_embed_key,
                prefs.getString(PREF_EMBED_KEY, ""), true);
        EditText embedModel = field(layout, R.string.settings_embed_model,
                prefs.getString(PREF_EMBED_MODEL, ""), false);
        EditText kokoroUrl = field(layout, R.string.settings_kokoro_url,
                prefs.getString(PREF_KOKORO_URL, ""), false);
        EditText kokoroToken = field(layout, R.string.settings_kokoro_token,
                prefs.getString(PREF_KOKORO_TOKEN, ""), true);
        EditText kokoroVoice = field(layout, R.string.settings_kokoro_voice,
                prefs.getString(PREF_KOKORO_VOICE, ""), false);

        // Document + actions
        Button loadSample = new Button(this);
        loadSample.setText(R.string.settings_load_sample);
        loadSample.setOnClickListener(v -> {
            loadAndIndex(readRawResource());
            Toast.makeText(this, R.string.settings_load_sample, Toast.LENGTH_SHORT).show();
        });
        layout.addView(loadSample);

        EditText pasteDoc = field(layout, R.string.settings_paste_hint, "", false);
        pasteDoc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pasteDoc.setMinLines(3);

        Button syncModels = new Button(this);
        syncModels.setText(R.string.settings_sync_models);
        syncModels.setOnClickListener(v -> new Thread(() ->
                appendConsole(team.models().syncFromManifest())).start());
        layout.addView(syncModels);

        EditText testMsg = field(layout, R.string.settings_test_hint, "", false);
        Button testSend = new Button(this);
        testSend.setText(R.string.settings_test_send);
        testSend.setOnClickListener(v -> {
            String msg = testMsg.getText().toString().trim();
            if (msg.isEmpty()) {
                return;
            }
            appendConsole("you: " + msg);
            new Thread(() -> {
                String reply = team.runMainAgent(msg);
                appendConsole("jarvis: " + reply);
                runOnUiThread(() -> engine.speakReply(reply, null));
            }).start();
        });
        layout.addView(testSend);

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(layout);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.edit()
                            .putString(PREF_REALTIME_KEY, val(realtimeKey))
                            .putString(PREF_REALTIME_MODEL, val(realtimeModel))
                            .putString(PREF_REALTIME_VOICE, val(realtimeVoice))
                            .putString(PREF_ANTHROPIC_KEY, val(anthropicKey))
                            .putString(PREF_COMPAT_KEY, val(compatKey))
                            .putString(PREF_A2A_URL, val(a2aUrl))
                            .putString(PREF_SANDBOX_URL, val(sandboxUrl))
                            .putString(PREF_SANDBOX_TOKEN, val(sandboxToken))
                            .putString(PREF_MANIFEST_URL, val(manifestUrl))
                            .putString(PREF_EMBED_BASE_URL, val(embedBaseUrl))
                            .putString(PREF_EMBED_KEY, val(embedKey))
                            .putString(PREF_EMBED_MODEL, val(embedModel))
                            .putString(PREF_KOKORO_URL, val(kokoroUrl))
                            .putString(PREF_KOKORO_TOKEN, val(kokoroToken))
                            .putString(PREF_KOKORO_VOICE, val(kokoroVoice))
                            .apply();
                    String pasted = pasteDoc.getText().toString().trim();
                    if (!pasted.isEmpty()) {
                        loadAndIndex(pasted);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText field(LinearLayout parent, int hintRes, String value, boolean secret) {
        EditText f = new EditText(this);
        f.setHint(hintRes);
        f.setText(value);
        if (secret) {
            f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(f);
        return f;
    }

    private static String val(EditText field) {
        return field.getText().toString().trim();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String readRawResource() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.sample_research),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.voice_sample_error, Toast.LENGTH_SHORT).show();
        }
        return sb.toString();
    }
}
