package com.mavve.myactionbar.memory;

import android.content.Context;
import android.content.SharedPreferences;

import com.mavve.myactionbar.llm.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Text -> vector, via any OpenAI-format /embeddings endpoint (OpenAI, Voyage,
 * a local sentence-transformers server, etc.). Configured in settings; when
 * no provider is configured the second brain degrades gracefully to keyword
 * retrieval, so RAG is an upgrade, never a requirement.
 */
public class EmbeddingClient {

    private static final String PREFS = "voice_agent";
    private static final String PREF_BASE_URL = "embed_base_url";
    private static final String PREF_API_KEY = "embed_api_key";
    private static final String PREF_MODEL = "embed_model";
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final SharedPreferences prefs;

    public EmbeddingClient(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return !prefs.getString(PREF_BASE_URL, "").isEmpty()
                && !prefs.getString(PREF_API_KEY, "").isEmpty();
    }

    /** Blocking; call from a background thread. Throws if not configured. */
    public float[] embed(String text) throws Exception {
        String baseUrl = prefs.getString(PREF_BASE_URL, "");
        String apiKey = prefs.getString(PREF_API_KEY, "");
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            throw new Exception("No embedding provider configured.");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String model = prefs.getString(PREF_MODEL, "");
        if (model.isEmpty()) {
            model = DEFAULT_MODEL;
        }
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("input", text);
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + apiKey);

        JSONObject response = new JSONObject(
                HttpJson.post(baseUrl + "/embeddings", headers, body));
        JSONArray vector = response.getJSONArray("data")
                .getJSONObject(0).getJSONArray("embedding");
        float[] result = new float[vector.length()];
        for (int i = 0; i < vector.length(); i++) {
            result[i] = (float) vector.getDouble(i);
        }
        return result;
    }
}
