package com.mavve.myactionbar.remote;

import android.content.Context;
import android.content.SharedPreferences;

import com.mavve.myactionbar.llm.HttpJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.UUID;

/**
 * Connector to a self-hosted Agent Zero instance over its A2A endpoint
 * (JSON-RPC: message/send + tasks/get; auth token lives in the URL path,
 * e.g. https://your-vps/a2a/t-TOKEN).
 *
 * This is the "backend brain" split: the phone keeps voice, playback, and
 * quick turns; anything heavy — long browsing sessions, real desktop work,
 * big builds — is delegated to Agent Zero and collected later. Delegation is
 * asynchronous on purpose: the tool returns a task id immediately so the
 * voice loop stays responsive, and the result is fetched when the user (or
 * agent) asks.
 *
 * Parsing is deliberately tolerant of response shape (message vs task,
 * artifacts vs history) so minor upstream changes don't break delegation.
 */
public class AgentZeroClient {

    private static final String PREFS = "voice_agent";
    private static final String PREF_A2A_URL = "a2a_url";

    private final SharedPreferences prefs;

    public AgentZeroClient(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return !prefs.getString(PREF_A2A_URL, "").isEmpty();
    }

    /** Send a task; returns either the final answer or a task id to poll. */
    public String delegate(String task) {
        String url = prefs.getString(PREF_A2A_URL, "");
        if (url.isEmpty()) {
            return "No Agent Zero endpoint configured. Set the A2A URL in settings.";
        }
        try {
            JSONObject message = new JSONObject()
                    .put("role", "user")
                    .put("messageId", UUID.randomUUID().toString())
                    .put("parts", new JSONArray().put(new JSONObject()
                            .put("kind", "text")
                            .put("text", task)));
            JSONObject body = rpc("message/send",
                    new JSONObject().put("message", message));
            JSONObject response = new JSONObject(
                    HttpJson.post(url, new HashMap<String, String>(), body));
            JSONObject result = response.optJSONObject("result");
            if (result == null) {
                return "Agent Zero error: " + response.optString("error", response.toString());
            }
            // Either a finished message right away...
            if ("message".equals(result.optString("kind"))) {
                return "Agent Zero replied: " + extractText(result);
            }
            // ...or a long-running task to poll.
            String taskId = result.optString("id", "");
            String state = result.optJSONObject("status") != null
                    ? result.getJSONObject("status").optString("state", "submitted")
                    : "submitted";
            if ("completed".equals(state)) {
                return "Agent Zero completed: " + extractText(result);
            }
            return "Delegated to Agent Zero. task_id: " + taskId + " (state: " + state
                    + "). Use check_delegated_task later to collect the result.";
        } catch (Exception e) {
            return "Agent Zero delegation failed: " + e.getMessage();
        }
    }

    /** Poll a previously delegated task. */
    public String check(String taskId) {
        String url = prefs.getString(PREF_A2A_URL, "");
        if (url.isEmpty()) {
            return "No Agent Zero endpoint configured.";
        }
        try {
            JSONObject body = rpc("tasks/get", new JSONObject().put("id", taskId));
            JSONObject response = new JSONObject(
                    HttpJson.post(url, new HashMap<String, String>(), body));
            JSONObject result = response.optJSONObject("result");
            if (result == null) {
                return "Agent Zero error: " + response.optString("error", response.toString());
            }
            String state = result.optJSONObject("status") != null
                    ? result.getJSONObject("status").optString("state", "unknown")
                    : "unknown";
            if ("completed".equals(state)) {
                return "Completed: " + extractText(result);
            }
            if ("failed".equals(state) || "canceled".equals(state)) {
                return "Task " + state + ": " + extractText(result);
            }
            return "Still " + state + ". Check again later.";
        } catch (Exception e) {
            return "Agent Zero check failed: " + e.getMessage();
        }
    }

    private static JSONObject rpc(String method, JSONObject params) throws Exception {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", UUID.randomUUID().toString())
                .put("method", method)
                .put("params", params);
    }

    /** Pull text parts out of whatever shape came back. */
    private static String extractText(JSONObject result) {
        StringBuilder sb = new StringBuilder();
        appendParts(sb, result.optJSONArray("parts"));
        JSONArray artifacts = result.optJSONArray("artifacts");
        if (artifacts != null) {
            for (int i = 0; i < artifacts.length(); i++) {
                appendParts(sb, artifacts.optJSONObject(i) == null ? null
                        : artifacts.optJSONObject(i).optJSONArray("parts"));
            }
        }
        JSONObject status = result.optJSONObject("status");
        if (status != null && status.optJSONObject("message") != null) {
            appendParts(sb, status.getJSONObject("message").optJSONArray("parts"));
        }
        JSONArray history = result.optJSONArray("history");
        if (sb.length() == 0 && history != null && history.length() > 0) {
            appendParts(sb, history.optJSONObject(history.length() - 1) == null ? null
                    : history.optJSONObject(history.length() - 1).optJSONArray("parts"));
        }
        return sb.length() == 0 ? result.toString() : sb.toString().trim();
    }

    private static void appendParts(StringBuilder sb, JSONArray parts) {
        if (parts == null) {
            return;
        }
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && part.has("text")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part.optString("text", ""));
            }
        }
    }
}
