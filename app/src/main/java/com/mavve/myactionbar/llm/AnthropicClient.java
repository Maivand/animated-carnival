package com.mavve.myactionbar.llm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Claude Messages API client. The native dialect — no translation needed. */
public class AnthropicClient implements LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final String apiKey;

    public AnthropicClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public JSONObject chat(String model, String system, JSONArray messages, JSONArray tools,
                           int maxTokens) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("system", system)
                .put("messages", messages);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");

        JSONObject response = new JSONObject(HttpJson.post(API_URL, headers, body));
        return new JSONObject()
                .put("content", response.getJSONArray("content"))
                .put("stop_reason", response.optString("stop_reason", "end_turn"));
    }
}
