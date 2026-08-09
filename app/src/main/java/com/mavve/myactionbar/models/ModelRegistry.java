package com.mavve.myactionbar.models;

import android.content.Context;
import android.content.SharedPreferences;

import com.mavve.myactionbar.llm.HttpJson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The installed-model catalog, persisted as JSON in SharedPreferences.
 *
 * Ships with Claude seeds and can sync from a remote manifest URL: any model
 * present in the manifest but not in the registry is auto-installed, and
 * updated priors are merged in. Point the manifest at a feed you trust and
 * "a better model came out" turns into "the router started using it" with no
 * app update. Learned win rates in ContextDatabase always outrank priors, so
 * a manifest can suggest but not lie its way to the top.
 */
public class ModelRegistry {

    private static final String PREFS = "voice_agent";
    private static final String PREF_MODELS = "model_registry_json";
    private static final String PREF_MANIFEST_URL = "model_manifest_url";

    private final SharedPreferences prefs;

    public ModelRegistry(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<ModelSpec> all() {
        String json = prefs.getString(PREF_MODELS, "");
        if (json.isEmpty()) {
            List<ModelSpec> seeds = seedModels();
            save(seeds);
            return seeds;
        }
        try {
            List<ModelSpec> result = new ArrayList<>();
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(ModelSpec.fromJson(array.getJSONObject(i)));
            }
            return result;
        } catch (JSONException e) {
            List<ModelSpec> seeds = seedModels();
            save(seeds);
            return seeds;
        }
    }

    public synchronized void install(ModelSpec spec) {
        List<ModelSpec> models = all();
        List<ModelSpec> updated = new ArrayList<>();
        boolean replaced = false;
        for (ModelSpec existing : models) {
            if (existing.id.equals(spec.id)) {
                updated.add(spec);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(spec);
        }
        save(updated);
    }

    /**
     * Fetch the configured manifest and merge it in. Returns a human-readable
     * summary of what was installed or updated. Call from a background thread.
     */
    public String syncFromManifest() {
        String url = prefs.getString(PREF_MANIFEST_URL, "");
        if (url.isEmpty()) {
            return "No model manifest URL configured.";
        }
        try {
            JSONArray manifest = new JSONArray(HttpJson.get(url));
            int installed = 0;
            int updated = 0;
            synchronized (this) {
                List<ModelSpec> current = all();
                for (int i = 0; i < manifest.length(); i++) {
                    ModelSpec incoming = ModelSpec.fromJson(manifest.getJSONObject(i));
                    boolean existed = false;
                    for (ModelSpec existing : current) {
                        if (existing.id.equals(incoming.id)) {
                            existed = true;
                            break;
                        }
                    }
                    install(incoming);
                    if (existed) {
                        updated++;
                    } else {
                        installed++;
                    }
                }
            }
            return "Manifest sync: " + installed + " new model(s) installed, "
                    + updated + " updated.";
        } catch (Exception e) {
            return "Manifest sync failed: " + e.getMessage();
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (ModelSpec spec : all()) {
            sb.append(spec.id).append(" (").append(spec.provider).append(")");
            if (!spec.note.isEmpty()) {
                sb.append(" — ").append(spec.note);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private synchronized void save(List<ModelSpec> models) {
        try {
            JSONArray array = new JSONArray();
            for (ModelSpec spec : models) {
                array.put(spec.toJson());
            }
            prefs.edit().putString(PREF_MODELS, array.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private static List<ModelSpec> seedModels() {
        List<ModelSpec> seeds = new ArrayList<>();

        Map<String, Double> sonnet = new HashMap<>();
        sonnet.put("default", 0.85);
        sonnet.put("coding", 0.9);
        sonnet.put("research", 0.88);
        sonnet.put("planning", 0.88);
        seeds.add(new ModelSpec("claude-sonnet-5", ModelSpec.PROVIDER_ANTHROPIC, "",
                sonnet, "strong generalist, best default for coding and research"));

        Map<String, Double> haiku = new HashMap<>();
        haiku.put("default", 0.6);
        haiku.put("chat", 0.85);
        haiku.put("voice_control", 0.9);
        haiku.put("summarization", 0.75);
        seeds.add(new ModelSpec("claude-haiku-4-5-20251001", ModelSpec.PROVIDER_ANTHROPIC, "",
                haiku, "fast and cheap, best for chat and quick voice turns"));

        Map<String, Double> opus = new HashMap<>();
        opus.put("default", 0.87);
        opus.put("coding", 0.92);
        opus.put("planning", 0.92);
        seeds.add(new ModelSpec("claude-opus-5", ModelSpec.PROVIDER_ANTHROPIC, "",
                opus, "deepest reasoning, slower; use for hard coding and planning"));

        return seeds;
    }
}
