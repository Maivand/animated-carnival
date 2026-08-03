package com.mavve.myactionbar;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mavve.myactionbar.voice.ClaudeAgent;
import com.mavve.myactionbar.voice.PlaybackEngine;
import com.mavve.myactionbar.voice.VoiceCommandRouter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice agent screen: load a long document, have it read aloud chunk by chunk
 * like a podcast, and control playback by voice. Simple commands ("pause",
 * "go back 10 seconds", "read it all") are handled entirely on-device by
 * VoiceCommandRouter — no API call, no tokens. Everything else goes to the
 * Claude agent, which controls the same playback engine through tool calls.
 */
public class VoiceAgentActivity extends AppCompatActivity implements PlaybackEngine.Listener {

    private static final int REQUEST_SPEECH = 42;
    private static final String PREFS = "voice_agent";
    private static final String PREF_API_KEY = "anthropic_api_key";

    private PlaybackEngine engine;
    private TextView statusView;
    private TextView nowReadingView;
    private ProgressBar progressBar;
    private EditText documentInput;
    private boolean agentBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_agent);
        setTitle(R.string.voice_agent_title);

        statusView = findViewById(R.id.voice_status);
        nowReadingView = findViewById(R.id.voice_now_reading);
        progressBar = findViewById(R.id.voice_progress);
        documentInput = findViewById(R.id.voice_document_input);

        engine = new PlaybackEngine(this, this);

        Button loadSample = findViewById(R.id.btn_load_sample);
        Button loadPasted = findViewById(R.id.btn_load_pasted);
        Button readAll = findViewById(R.id.btn_read_all);
        Button pauseResume = findViewById(R.id.btn_pause_resume);
        Button back10 = findViewById(R.id.btn_back_10);
        Button forward10 = findViewById(R.id.btn_forward_10);
        Button talk = findViewById(R.id.btn_talk);
        Button apiKey = findViewById(R.id.btn_api_key);

        loadSample.setOnClickListener(v -> {
            String sample = readRawResource();
            documentInput.setText(sample);
            engine.loadDocument(sample);
        });
        loadPasted.setOnClickListener(v ->
                engine.loadDocument(documentInput.getText().toString()));
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
        apiKey.setOnClickListener(v -> showApiKeyDialog());
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
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_listening_prompt));
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

        // 1) Free, instant, on-device: playback commands.
        if (VoiceCommandRouter.tryHandle(utterance, engine)) {
            return;
        }

        // 2) Everything else: the Claude agent with playback tools.
        String key = getPrefs().getString(PREF_API_KEY, "");
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.voice_need_api_key, Toast.LENGTH_LONG).show();
            showApiKeyDialog();
            return;
        }
        if (agentBusy) {
            Toast.makeText(this, R.string.voice_agent_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        agentBusy = true;
        statusView.setText(R.string.voice_thinking);
        ClaudeAgent agent = new ClaudeAgent(key, engine);
        new Thread(() -> {
            String reply;
            try {
                reply = agent.ask(utterance);
            } catch (Exception e) {
                reply = getString(R.string.voice_agent_error);
            }
            final String spoken = reply;
            runOnUiThread(() -> {
                agentBusy = false;
                if (!spoken.isEmpty()) {
                    statusView.setText(spoken);
                }
                engine.speakReply(spoken, null);
            });
        }).start();
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

    // ---- helpers ----------------------------------------------------------

    private void showApiKeyDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.voice_api_key_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_api_key_title)
                .setMessage(R.string.voice_api_key_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        getPrefs().edit()
                                .putString(PREF_API_KEY, input.getText().toString().trim())
                                .apply())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String readRawResource() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.sample_research), StandardCharsets.UTF_8))) {
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
