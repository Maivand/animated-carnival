package com.mavve.myactionbar.agent;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Prompts as data, not code (the Agent Zero lesson). Templates ship as app
 * assets (assets/prompts/*.txt) and can be overridden by files in
 * filesDir/prompts/ — which is how set_behavior works: standing user
 * instructions land in behavior.txt and are appended to every agent's system
 * prompt from then on, surviving restarts. Editing behavior means editing a
 * text file, not recompiling.
 */
public class PromptStore {

    public static final String MAIN = "main.txt";
    public static final String WORKER = "worker.txt";
    private static final String BEHAVIOR = "behavior.txt";

    private final Context app;
    private final File overrideDir;

    public PromptStore(Context context) {
        this.app = context.getApplicationContext();
        this.overrideDir = new File(app.getFilesDir(), "prompts");
        if (!overrideDir.exists()) {
            overrideDir.mkdirs();
        }
    }

    /** Override file if present, else the bundled asset, else empty. */
    public String get(String name) {
        File override = new File(overrideDir, name);
        if (override.exists()) {
            try (FileInputStream in = new FileInputStream(override)) {
                return readAll(in);
            } catch (Exception ignored) {
            }
        }
        try (InputStream in = app.getAssets().open("prompts/" + name)) {
            return readAll(in);
        } catch (Exception e) {
            return "";
        }
    }

    /** Standing user instructions appended to every system prompt. */
    public String behavior() {
        File file = new File(overrideDir, BEHAVIOR);
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return readAll(in).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Append an instruction to the persistent behavior file. */
    public void addBehavior(String instruction) {
        String current = behavior();
        String updated = current.isEmpty()
                ? "- " + instruction.trim()
                : current + "\n- " + instruction.trim();
        try (FileOutputStream out = new FileOutputStream(new File(overrideDir, BEHAVIOR))) {
            out.write(updated.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public void clearBehavior() {
        new File(overrideDir, BEHAVIOR).delete();
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
