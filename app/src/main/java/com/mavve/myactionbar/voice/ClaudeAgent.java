package com.mavve.myactionbar.voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal Claude Messages API client with a tool-use loop. The tools do not
 * fetch data from the model — they control the local {@link PlaybackEngine}.
 * That inversion is the token saver: when the user says "read the whole
 * document", the model answers with one small read_document tool call and the
 * on-device TTS does the actual reading; the 100 pages of text are never sent
 * through the model. Similarly, "clarify what you said 30 seconds ago" pulls
 * only that 30-second transcript window into context, not the whole document.
 *
 * Everything runs on the caller's (background) thread.
 */
public class ClaudeAgent {

    /** Swap for claude-haiku-4-5-20251001 if you want lower latency/cost. */
    private static final String MODEL = "claude-sonnet-5";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int MAX_TOKENS = 400;

    private static final String SYSTEM_PROMPT =
            "You are a voice assistant inside an Android app that reads long documents "
            + "aloud with on-device text-to-speech. Your replies are spoken aloud, so keep "
            + "them to one or two short sentences with no markdown or lists. "
            + "You control playback exclusively through tools. NEVER read or quote the "
            + "document aloud yourself — call read_document and the device reads it "
            + "locally, which costs no tokens. When the user asks you to clarify or "
            + "explain something they just heard, call get_transcript_window with an "
            + "appropriate seconds_back value and answer from that window only. "
            + "After acting, reply with at most one short confirmation or answer.";

    private final String apiKey;
    private final PlaybackEngine engine;

    public ClaudeAgent(String apiKey, PlaybackEngine engine) {
        this.apiKey = apiKey;
        this.engine = engine;
    }

    /**
     * Send one user utterance through the agent loop, executing any tool calls
     * against the playback engine, and return the final spoken reply.
     */
    public String ask(String utterance) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", utterance));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject response = post(buildRequest(messages));
            JSONArray content = response.getJSONArray("content");
            String stopReason = response.optString("stop_reason", "");

            // Echo the assistant turn back into the conversation as-is.
            messages.put(new JSONObject()
                    .put("role", "assistant")
                    .put("content", content));

            if (!"tool_use".equals(stopReason)) {
                return extractText(content);
            }

            JSONArray toolResults = new JSONArray();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("tool_use".equals(block.getString("type"))) {
                    String result = executeTool(
                            block.getString("name"),
                            block.optJSONObject("input"));
                    toolResults.put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.getString("id"))
                            .put("content", result));
                }
            }
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", toolResults));
        }
        return "Sorry, that took too many steps.";
    }

    // ---- tool dispatch ----------------------------------------------------

    private String executeTool(String name, JSONObject input) {
        if (input == null) {
            input = new JSONObject();
        }
        try {
            switch (name) {
                case "read_document":
                    if ("current".equals(input.optString("from", "beginning"))) {
                        engine.resume();
                    } else {
                        engine.readFromBeginning();
                    }
                    return "Started reading. " + engine.statusDescription();
                case "pause_playback":
                    engine.pause();
                    return "Paused. " + engine.statusDescription();
                case "resume_playback":
                    engine.resume();
                    return "Resumed. " + engine.statusDescription();
                case "rewind_playback":
                    engine.rewindSeconds(input.optDouble("seconds", 10));
                    return "Rewound. " + engine.statusDescription();
                case "forward_playback":
                    engine.forwardSeconds(input.optDouble("seconds", 10));
                    return "Skipped forward. " + engine.statusDescription();
                case "set_speech_rate":
                    engine.setSpeechRate((float) input.optDouble("rate", 1.0));
                    return "Speed changed. " + engine.statusDescription();
                case "jump_to_chunk":
                    engine.jumpToChunk(input.optInt("chunk_index", 0));
                    return "Jumped. " + engine.statusDescription();
                case "get_playback_status":
                    return engine.statusDescription();
                case "get_document_outline":
                    return engine.outline();
                case "get_transcript_window":
                    String window = engine.transcriptWindow(input.optDouble("seconds_back", 30));
                    return window.isEmpty()
                            ? "Nothing has been read aloud yet."
                            : "Text spoken in that window: " + window;
                default:
                    return "Unknown tool: " + name;
            }
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    // ---- request building -------------------------------------------------

    private JSONObject buildRequest(JSONArray messages) throws JSONException {
        return new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", MAX_TOKENS)
                .put("system", SYSTEM_PROMPT)
                .put("tools", buildTools())
                .put("messages", messages);
    }

    private JSONArray buildTools() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("read_document",
                "Start reading the loaded document aloud on the device, chunk by chunk like a "
                        + "podcast. The device does the reading; you do not see the text.",
                new JSONObject().put("from", prop("string",
                        "\"beginning\" to start over, \"current\" to resume from the current position."))));
        tools.put(tool("pause_playback", "Pause the document reading.", null));
        tools.put(tool("resume_playback", "Resume reading from the current position.", null));
        tools.put(tool("rewind_playback",
                "Jump back in the spoken audio by roughly this many seconds.",
                new JSONObject().put("seconds", prop("number", "How many seconds to go back."))));
        tools.put(tool("forward_playback",
                "Skip ahead in the spoken audio by roughly this many seconds.",
                new JSONObject().put("seconds", prop("number", "How many seconds to skip forward."))));
        tools.put(tool("set_speech_rate",
                "Change the reading speed.",
                new JSONObject().put("rate", prop("number", "Speech rate between 0.5 and 2.0; 1.0 is normal."))));
        tools.put(tool("jump_to_chunk",
                "Jump playback to a specific chunk index from get_document_outline.",
                new JSONObject().put("chunk_index", prop("integer", "Zero-based chunk index."))));
        tools.put(tool("get_playback_status",
                "Get current position, total chunks, playing/paused state, and speed.", null));
        tools.put(tool("get_document_outline",
                "Get a sampled outline of the document with chunk indices, for navigation.", null));
        tools.put(tool("get_transcript_window",
                "Get exactly the text that was spoken aloud during the last N seconds. Use this "
                        + "to answer questions like \"clarify what you just said\" without "
                        + "loading the whole document.",
                new JSONObject().put("seconds_back",
                        prop("number", "How many seconds of recent speech to fetch."))));
        return tools;
    }

    private static JSONObject prop(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject tool(String name, String description, JSONObject properties)
            throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object");
        schema.put("properties", properties == null ? new JSONObject() : properties);
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("input_schema", schema);
    }

    // ---- HTTP -------------------------------------------------------------

    private JSONObject post(JSONObject body) throws Exception {
        HttpsURLConnection connection =
                (HttpsURLConnection) new URL(API_URL).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json");
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new Exception("API error " + code + ": " + response);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String extractText(JSONArray content) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            if ("text".equals(block.getString("type"))) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(block.getString("text"));
            }
        }
        return sb.toString();
    }
}
