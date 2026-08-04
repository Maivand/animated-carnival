package com.mavve.myactionbar;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mavve.myactionbar.agent.AgentTeam;
import com.mavve.myactionbar.agent.MediaSurface;
import com.mavve.myactionbar.voice.DocumentChunker;
import com.mavve.myactionbar.voice.PlaybackEngine;
import com.mavve.myactionbar.voice.VoiceCommandRouter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Jarvis's main screen. Voice flow:
 *
 *   1. Speech is first offered to VoiceCommandRouter — playback commands
 *      ("pause", "go back 10 seconds", "read it all") execute on-device with
 *      zero API tokens.
 *   2. Everything else goes to the AgentTeam: the main agent picks the best
 *      model via the router, uses tools (playback, memory, workspace,
 *      spawning), and may fan out into sub-agents. Its final reply is spoken.
 *
 * The console at the bottom shows the swarm working: which agent, which
 * model, which tool.
 */
public class VoiceAgentActivity extends AppCompatActivity
        implements PlaybackEngine.Listener, MediaSurface {

    private static final int REQUEST_SPEECH = 42;
    private static final String PREFS = "voice_agent";
    private static final String PREF_ANTHROPIC_KEY = "anthropic_api_key";
    private static final String PREF_COMPAT_KEY = "compat_api_key";
    private static final String PREF_SANDBOX_URL = "sandbox_url";
    private static final String PREF_SANDBOX_TOKEN = "sandbox_token";
    private static final String PREF_MANIFEST_URL = "model_manifest_url";
    private static final String PREF_EMBED_BASE_URL = "embed_base_url";
    private static final String PREF_EMBED_KEY = "embed_api_key";
    private static final String PREF_EMBED_MODEL = "embed_model";
    private static final int CONSOLE_MAX_CHARS = 4000;

    private PlaybackEngine engine;
    private AgentTeam team;
    private TextView statusView;
    private TextView nowReadingView;
    private TextView consoleView;
    private ProgressBar progressBar;
    private EditText documentInput;
    private boolean agentBusy = false;

    private View mediaPanel;
    private ImageView mediaImage;
    private VideoView mediaVideo;
    private WebView mediaWeb;
    private TextView mediaCaption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_agent);
        setTitle(R.string.app_name);

        statusView = findViewById(R.id.voice_status);
        nowReadingView = findViewById(R.id.voice_now_reading);
        consoleView = findViewById(R.id.voice_console);
        progressBar = findViewById(R.id.voice_progress);
        documentInput = findViewById(R.id.voice_document_input);
        consoleView.setMovementMethod(new ScrollingMovementMethod());

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

        Button loadSample = findViewById(R.id.btn_load_sample);
        Button loadPasted = findViewById(R.id.btn_load_pasted);
        Button readAll = findViewById(R.id.btn_read_all);
        Button pauseResume = findViewById(R.id.btn_pause_resume);
        Button back10 = findViewById(R.id.btn_back_10);
        Button forward10 = findViewById(R.id.btn_forward_10);
        Button talk = findViewById(R.id.btn_talk);
        Button stop = findViewById(R.id.btn_stop);
        Button settings = findViewById(R.id.btn_settings);
        Button models = findViewById(R.id.btn_models);

        loadSample.setOnClickListener(v -> {
            String sample = readRawResource();
            documentInput.setText(sample);
            loadAndIndex(sample);
        });
        loadPasted.setOnClickListener(v ->
                loadAndIndex(documentInput.getText().toString()));
        readAll.setOnClickListener(v -> engine.readFromBeginning());
        pauseResume.setOnClickListener(v -> {
            if (engine.isPlaying()) {
                engine.pause();
            } else {
                engine.resume();
            }
        });
        back10.setOnClickListener(v -> engine.rewindSeconds(10));
        forward10.setOnClickListener(v -> engine.forwardSeconds(10));
        talk.setOnClickListener(v -> startListening());
        stop.setOnClickListener(v -> {
            team.cancelAll();
            engine.pause();
            statusView.setText(R.string.voice_stopping);
        });
        settings.setOnClickListener(v -> showSettingsDialog());
        models.setOnClickListener(v -> showModels());
    }

    @Override
    protected void onDestroy() {
        engine.shutdown();
        super.onDestroy();
    }

    // ---- voice input ------------------------------------------------------

    private void startListening() {
        // Pause reading so the recognizer does not hear the TTS voice.
        engine.pause();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.voice_listening_prompt));
        try {
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, R.string.voice_no_recognizer, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH || resultCode != RESULT_OK || data == null) {
            return;
        }
        ArrayList<String> results =
                data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) {
            return;
        }
        handleUtterance(results.get(0));
    }

    private void handleUtterance(String utterance) {
        statusView.setText(getString(R.string.voice_heard, utterance));
        appendConsole("you: " + utterance);

        // 1) Free, instant, on-device: playback commands.
        if (VoiceCommandRouter.tryHandle(utterance, engine)) {
            return;
        }

        // 2) Everything else: the agent team.
        if (getPrefs().getString(PREF_ANTHROPIC_KEY, "").isEmpty()
                && getPrefs().getString(PREF_COMPAT_KEY, "").isEmpty()) {
            Toast.makeText(this, R.string.voice_need_api_key, Toast.LENGTH_LONG).show();
            showSettingsDialog();
            return;
        }
        if (agentBusy) {
            Toast.makeText(this, R.string.voice_agent_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        agentBusy = true;
        statusView.setText(R.string.voice_thinking);
        new Thread(() -> {
            String reply = team.runMainAgent(utterance);
            runOnUiThread(() -> {
                agentBusy = false;
                if (!reply.isEmpty()) {
                    statusView.setText(reply);
                    appendConsole("jarvis: " + reply);
                }
                engine.speakReply(reply, null);
            });
        }).start();
    }

    /**
     * Load a document into the playback engine AND index it into the second
     * brain (RAG corpus) in the background, so both "read it all" and "what
     * did it say about X" work immediately.
     */
    private void loadAndIndex(String text) {
        engine.loadDocument(text);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String firstLine = text.trim().split("\n", 2)[0].trim();
        String title = firstLine.substring(0, Math.min(60, firstLine.length()));
        appendConsole("indexing \"" + title + "\" into second brain…");
        new Thread(() -> {
            String summary = team.brain().indexDocument(title, DocumentChunker.chunk(text));
            appendConsole(summary);
        }).start();
    }

    // ---- MediaSurface ------------------------------------------------------

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
                Toast.makeText(this, R.string.media_video_failed, Toast.LENGTH_SHORT)
                        .show();
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
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(url).openConnection();
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
        nowReadingView.setText(text);
        progressBar.setMax(total);
        progressBar.setProgress(index + 1);
        statusView.setText(getString(R.string.voice_reading_progress, index + 1, total));
    }

    @Override
    public void onPlaybackFinished() {
        statusView.setText(R.string.voice_done_reading);
    }

    @Override
    public void onStatus(String status) {
        statusView.setText(status);
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

    // ---- settings and models ----------------------------------------------

    private void showSettingsDialog() {
        SharedPreferences prefs = getPrefs();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText anthropicKey = settingsField(layout, R.string.settings_anthropic_key,
                prefs.getString(PREF_ANTHROPIC_KEY, ""), true);
        EditText compatKey = settingsField(layout, R.string.settings_compat_key,
                prefs.getString(PREF_COMPAT_KEY, ""), true);
        EditText sandboxUrl = settingsField(layout, R.string.settings_sandbox_url,
                prefs.getString(PREF_SANDBOX_URL, ""), false);
        EditText sandboxToken = settingsField(layout, R.string.settings_sandbox_token,
                prefs.getString(PREF_SANDBOX_TOKEN, ""), true);
        EditText manifestUrl = settingsField(layout, R.string.settings_manifest_url,
                prefs.getString(PREF_MANIFEST_URL, ""), false);
        EditText embedBaseUrl = settingsField(layout, R.string.settings_embed_base_url,
                prefs.getString(PREF_EMBED_BASE_URL, ""), false);
        EditText embedKey = settingsField(layout, R.string.settings_embed_key,
                prefs.getString(PREF_EMBED_KEY, ""), true);
        EditText embedModel = settingsField(layout, R.string.settings_embed_model,
                prefs.getString(PREF_EMBED_MODEL, ""), false);

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(layout);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        prefs.edit()
                                .putString(PREF_ANTHROPIC_KEY,
                                        anthropicKey.getText().toString().trim())
                                .putString(PREF_COMPAT_KEY,
                                        compatKey.getText().toString().trim())
                                .putString(PREF_SANDBOX_URL,
                                        sandboxUrl.getText().toString().trim())
                                .putString(PREF_SANDBOX_TOKEN,
                                        sandboxToken.getText().toString().trim())
                                .putString(PREF_MANIFEST_URL,
                                        manifestUrl.getText().toString().trim())
                                .putString(PREF_EMBED_BASE_URL,
                                        embedBaseUrl.getText().toString().trim())
                                .putString(PREF_EMBED_KEY,
                                        embedKey.getText().toString().trim())
                                .putString(PREF_EMBED_MODEL,
                                        embedModel.getText().toString().trim())
                                .apply())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText settingsField(LinearLayout parent, int hintRes, String value,
                                   boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hintRes);
        field.setText(value);
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(field);
        return field;
    }

    private void showModels() {
        appendConsole("syncing model manifest…");
        new Thread(() -> {
            String syncResult = team.models().syncFromManifest();
            String report = syncResult + "\n\nInstalled models:\n"
                    + team.models().describe()
                    + "\nRouting:\n" + team.modelRouter().explainRouting()
                    + "\nScoreboard:\n" + team.database().scoreboard();
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(R.string.models_title)
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
        }).start();
    }

    // ---- helpers ----------------------------------------------------------

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
