package com.mavve.myactionbar.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Client for any OpenAI-compatible /chat/completions endpoint: OpenRouter,
 * OpenAI itself, Groq, Together, a local llama.cpp or Ollama server, and so
 * on. This one class is what makes the app model-agnostic — a new model from
 * any vendor becomes usable the moment the registry learns its id and base
 * URL, with no code change.
 *
 * Translates Anthropic-format messages/tools out, and OpenAI responses back
 * into Anthropic-style content blocks.
 */
public class OpenAiCompatClient implements LlmClient {

    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public JSONObject chat(String model, String system, JSONArray messages, JSONArray tools,
                           int maxTokens) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("messages", convertMessagesOut(system, messages));
        if (tools != null && tools.length() > 0) {
            body.put("tools", convertToolsOut(tools));
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("authorization", "Bearer " + apiKey);

        JSONObject response = new JSONObject(
                HttpJson.post(baseUrl + "/chat/completions", headers, body));
        return convertResponseIn(response);
    }

    // ---- outbound: Anthropic format -> OpenAI format ----------------------

    private JSONArray convertMessagesOut(String system, JSONArray messages) throws JSONException {
        JSONArray out = new JSONArray();
        if (system != null && !system.isEmpty()) {
            out.put(new JSONObject().put("role", "system").put("content", system));
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            String role = message.getString("role");
            Object content = message.get("content");

            if (content instanceof String) {
                out.put(new JSONObject().put("role", role).put("content", content));
                continue;
            }

            JSONArray blocks = (JSONArray) content;
            StringBuilder text = new StringBuilder();
            JSONArray toolCalls = new JSONArray();
            for (int b = 0; b < blocks.length(); b++) {
                JSONObject block = blocks.getJSONObject(b);
                String type = block.getString("type");
                if ("text".equals(type)) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(block.getString("text"));
                } else if ("tool_use".equals(type)) {
                    toolCalls.put(new JSONObject()
                            .put("id", block.getString("id"))
                            .put("type", "function")
                            .put("function", new JSONObject()
                                    .put("name", block.getString("name"))
                                    .put("arguments", block.optJSONObject("input") == null
                                            ? "{}"
                                            : block.getJSONObject("input").toString())));
                } else if ("tool_result".equals(type)) {
                    // Each tool result becomes its own role:"tool" message.
                    out.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", block.getString("tool_use_id"))
                            .put("content", block.optString("content", "")));
                }
            }
            if (text.length() > 0 || toolCalls.length() > 0) {
                JSONObject converted = new JSONObject().put("role", role);
                converted.put("content", text.length() > 0 ? text.toString() : JSONObject.NULL);
                if (toolCalls.length() > 0) {
                    converted.put("tool_calls", toolCalls);
                }
                out.put(converted);
            }
        }
        return out;
    }

    private JSONArray convertToolsOut(JSONArray tools) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            out.put(new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", tool.getString("name"))
                            .put("description", tool.optString("description", ""))
                            .put("parameters", tool.getJSONObject("input_schema"))));
        }
        return out;
    }

    // ---- inbound: OpenAI format -> Anthropic format -----------------------

    private JSONObject convertResponseIn(JSONObject response) throws JSONException {
        JSONObject message = response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message");
        JSONArray content = new JSONArray();

        String text = message.optString("content", "");
        if (!text.isEmpty() && !"null".equals(text)) {
            content.put(new JSONObject().put("type", "text").put("text", text));
        }

        JSONArray toolCalls = message.optJSONArray("tool_calls");
        boolean hasToolCalls = toolCalls != null && toolCalls.length() > 0;
        if (hasToolCalls) {
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject call = toolCalls.getJSONObject(i);
                JSONObject function = call.getJSONObject("function");
                JSONObject input;
                try {
                    input = new JSONObject(function.optString("arguments", "{}"));
                } catch (JSONException e) {
                    input = new JSONObject();
                }
                content.put(new JSONObject()
                        .put("type", "tool_use")
                        .put("id", call.getString("id"))
                        .put("name", function.getString("name"))
                        .put("input", input));
            }
        }
        return new JSONObject()
                .put("content", content)
                .put("stop_reason", hasToolCalls ? "tool_use" : "end_turn");
    }
}
