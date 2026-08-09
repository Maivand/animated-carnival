package com.mavve.myactionbar.models;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * One installable model. "Installing" an API-served model is just adding its
 * spec to the registry — id, provider, endpoint, and prior scores per task
 * category. Priors seed the router; real outcomes recorded in the context
 * database take over as evidence accumulates.
 */
public class ModelSpec {

    /** Providers understood by the client factory. */
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_OPENAI_COMPAT = "openai-compat";

    public final String id;
    public final String provider;
    /** Base URL for openai-compat providers; empty for Anthropic. */
    public final String baseUrl;
    /** Prior score 0..1 per task category (coding, research, chat, ...). */
    public final Map<String, Double> priors;
    public final String note;

    public ModelSpec(String id, String provider, String baseUrl,
                     Map<String, Double> priors, String note) {
        this.id = id;
        this.provider = provider;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.priors = priors == null ? new HashMap<String, Double>() : priors;
        this.note = note == null ? "" : note;
    }

    public double prior(String task) {
        Double value = priors.get(task);
        if (value != null) {
            return value;
        }
        Double fallback = priors.get("default");
        return fallback != null ? fallback : 0.5;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject priorsJson = new JSONObject();
        for (Map.Entry<String, Double> entry : priors.entrySet()) {
            priorsJson.put(entry.getKey(), entry.getValue());
        }
        return new JSONObject()
                .put("id", id)
                .put("provider", provider)
                .put("base_url", baseUrl)
                .put("priors", priorsJson)
                .put("note", note);
    }

    public static ModelSpec fromJson(JSONObject json) throws JSONException {
        Map<String, Double> priors = new HashMap<>();
        JSONObject priorsJson = json.optJSONObject("priors");
        if (priorsJson != null) {
            Iterator<String> keys = priorsJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                priors.put(key, priorsJson.getDouble(key));
            }
        }
        return new ModelSpec(
                json.getString("id"),
                json.optString("provider", PROVIDER_ANTHROPIC),
                json.optString("base_url", ""),
                priors,
                json.optString("note", ""));
    }
}
