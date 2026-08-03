package com.mavve.myactionbar.agent;

import com.mavve.myactionbar.memory.ContextDatabase;
import com.mavve.myactionbar.models.ModelRouter;
import com.mavve.myactionbar.voice.PlaybackEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * One agent: a model chosen by the router, a tool loop, and a set of tools
 * scoped to its role. The main (depth 0) voice agent gets playback tools;
 * every agent gets memory, workbench, model, and spawning tools — so agents
 * can delegate, and their delegates can delegate again, down to the team's
 * depth limit.
 *
 * The same loop runs regardless of which provider serves the model, because
 * every LlmClient speaks the normalized Anthropic block format.
 */
public class JarvisAgent {

    private static final int MAX_TOOL_ROUNDS = 12;
    private static final int MAX_TOKENS = 1024;
    private static final int MEMORY_SNIPPETS = 4;

    private final AgentTeam team;
    private final String name;
    private final int depth;
    private final PlaybackEngine playback; // null for sub-agents

    JarvisAgent(AgentTeam team, String name, int depth, PlaybackEngine playback) {
        this.team = team;
        this.name = name;
        this.depth = depth;
        this.playback = playback;
    }

    /** Run the full loop for one task and return the final answer text. */
    public String ask(String task) {
        String category = TaskClassifier.classify(task);
        ModelRouter.Pick pick;
        try {
            pick = team.router.pick(category);
        } catch (Exception e) {
            return "No model available: " + e.getMessage();
        }
        team.log(name + " [" + category + "] -> " + pick.spec.id);
        try {
            String answer = runLoop(pick, task);
            team.router.recordOutcome(pick.spec.id, category, true);
            return answer;
        } catch (Exception e) {
            team.router.recordOutcome(pick.spec.id, category, false);
            team.log(name + " failed: " + e.getMessage());
            return "Agent " + name + " hit an error: " + e.getMessage();
        }
    }

    private String runLoop(ModelRouter.Pick pick, String task) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", task));
        JSONArray tools = buildTools();
        String system = buildSystemPrompt(task);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject response = pick.client.chat(
                    pick.spec.id, system, messages, tools, MAX_TOKENS);
            JSONArray content = response.getJSONArray("content");
            messages.put(new JSONObject().put("role", "assistant").put("content", content));

            if (!"tool_use".equals(response.optString("stop_reason"))) {
                return extractText(content);
            }
            JSONArray toolResults = new JSONArray();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("tool_use".equals(block.getString("type"))) {
                    String toolName = block.getString("name");
                    team.log(name + " tool: " + toolName);
                    String result = executeTool(toolName, block.optJSONObject("input"));
                    toolResults.put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.getString("id"))
                            .put("content", result));
                }
            }
            messages.put(new JSONObject().put("role", "user").put("content", toolResults));
        }
        return "Stopped: too many tool rounds.";
    }

    // ---- system prompt -----------------------------------------------------

    private String buildSystemPrompt(String task) {
        StringBuilder sb = new StringBuilder();
        if (depth == 0) {
            sb.append("You are Jarvis, a voice assistant on the user's phone. Replies are ")
                    .append("spoken aloud: one or two short sentences, no markdown. ")
                    .append("NEVER read documents aloud yourself — use read_document so the ")
                    .append("device reads local text for free. For 'clarify what you said', ")
                    .append("use get_transcript_window and answer from that window only. ");
        } else {
            sb.append("You are ").append(name)
                    .append(", a worker agent (depth ").append(depth)
                    .append(") inside Jarvis. Return a concise, information-dense result ")
                    .append("to your parent agent; it is not spoken aloud. ");
        }
        sb.append("Delegate independent subtasks with spawn_agent instead of doing ")
                .append("everything serially. Save durable facts with remember; check recall ")
                .append("before asking the user for information they may have given before. ")
                .append("For coding tasks, use the workspace tools and iterate: write files, ")
                .append("run_command to test, read errors, fix, repeat.");

        List<ContextDatabase.Memory> memories = team.db.recall(task, MEMORY_SNIPPETS);
        if (!memories.isEmpty()) {
            sb.append("\n\nPossibly relevant memories:\n");
            for (ContextDatabase.Memory memory : memories) {
                sb.append("- [").append(memory.kind).append("] ")
                        .append(memory.content).append('\n');
            }
        }
        return sb.toString();
    }

    // ---- tool definitions --------------------------------------------------

    private JSONArray buildTools() throws JSONException {
        JSONArray tools = new JSONArray();

        // Playback tools: main voice agent only.
        if (playback != null) {
            tools.put(tool("read_document",
                    "Read the loaded document aloud on the device, chunk by chunk like a "
                            + "podcast. The device does the reading; you never see the text.",
                    obj().put("from", prop("string",
                            "\"beginning\" to start over, \"current\" to resume."))));
            tools.put(tool("pause_playback", "Pause the document reading.", null));
            tools.put(tool("resume_playback", "Resume reading from the current position.", null));
            tools.put(tool("rewind_playback", "Jump back N seconds in the spoken audio.",
                    obj().put("seconds", prop("number", "Seconds to go back."))));
            tools.put(tool("forward_playback", "Skip ahead N seconds in the spoken audio.",
                    obj().put("seconds", prop("number", "Seconds to skip forward."))));
            tools.put(tool("set_speech_rate", "Change the reading speed.",
                    obj().put("rate", prop("number", "0.5 to 2.0; 1.0 is normal."))));
            tools.put(tool("jump_to_chunk", "Jump to a chunk index from get_document_outline.",
                    obj().put("chunk_index", prop("integer", "Zero-based chunk index."))));
            tools.put(tool("get_playback_status",
                    "Current position, total chunks, playing state, speed.", null));
            tools.put(tool("get_document_outline",
                    "Sampled outline of the document with chunk indices.", null));
            tools.put(tool("get_transcript_window",
                    "Exactly the text spoken aloud during the last N seconds.",
                    obj().put("seconds_back", prop("number", "Seconds of recent speech."))));
        }

        // Memory tools: everyone.
        tools.put(tool("remember", "Save a durable fact, preference, or result to memory.",
                obj().put("content", prop("string", "What to remember."))
                        .put("kind", prop("string", "fact | preference | result | note"))
                        .put("tags", prop("string", "Comma-separated tags for retrieval."))));
        tools.put(tool("recall", "Search long-term memory.",
                obj().put("query", prop("string", "Keywords to search for."))));

        // Spawning tools: until the depth limit.
        if (depth < AgentTeam.MAX_DEPTH) {
            tools.put(tool("spawn_agent",
                    "Spawn a sub-agent for a subtask. Sub-agents share memory and the "
                            + "workspace and can spawn their own sub-agents. Use background "
                            + "true for independent work you will collect later.",
                    obj().put("name", prop("string", "Short role name, e.g. researcher."))
                            .put("task", prop("string", "Complete, self-contained instructions."))
                            .put("background", prop("boolean",
                                    "false (default): wait and get the result now. "
                                            + "true: get an agent id to poll."))));
            tools.put(tool("get_agent_result", "Fetch a background agent's result by id.",
                    obj().put("agent_id", prop("string", "Id returned by spawn_agent."))));
            tools.put(tool("list_agents", "List background agents and their state.", null));
        }

        // Workbench tools: everyone.
        tools.put(tool("write_file", "Create or overwrite a file in the shared workspace.",
                obj().put("path", prop("string", "Relative path, e.g. src/main.py."))
                        .put("content", prop("string", "Full file content."))));
        tools.put(tool("read_file", "Read a workspace file.",
                obj().put("path", prop("string", "Relative path."))));
        tools.put(tool("list_files", "List all workspace files.", null));
        tools.put(tool("run_command",
                "Run a shell command against the workspace in the sandbox (tests, builds). "
                        + "Returns stdout, stderr and exit code.",
                obj().put("command", prop("string", "Command to run, e.g. pytest -q."))));

        // Model-fleet tools: everyone.
        tools.put(tool("list_models", "List installed models and current routing.", null));
        tools.put(tool("sync_models",
                "Fetch the model manifest and auto-install any new models.", null));
        tools.put(tool("record_model_feedback",
                "Record that a model did well or poorly at a task category, adjusting "
                        + "future routing.",
                obj().put("model", prop("string", "Model id."))
                        .put("task", prop("string",
                                "coding | research | planning | chat | voice_control | summarization"))
                        .put("success", prop("boolean", "true if it did well."))));
        return tools;
    }

    // ---- tool execution ----------------------------------------------------

    private String executeTool(String toolName, JSONObject input) {
        if (input == null) {
            input = new JSONObject();
        }
        try {
            switch (toolName) {
                // playback (null-guarded: only offered when playback != null)
                case "read_document":
                    if ("current".equals(input.optString("from", "beginning"))) {
                        playback.resume();
                    } else {
                        playback.readFromBeginning();
                    }
                    return "Started reading. " + playback.statusDescription();
                case "pause_playback":
                    playback.pause();
                    return "Paused. " + playback.statusDescription();
                case "resume_playback":
                    playback.resume();
                    return "Resumed. " + playback.statusDescription();
                case "rewind_playback":
                    playback.rewindSeconds(input.optDouble("seconds", 10));
                    return "Rewound. " + playback.statusDescription();
                case "forward_playback":
                    playback.forwardSeconds(input.optDouble("seconds", 10));
                    return "Skipped forward. " + playback.statusDescription();
                case "set_speech_rate":
                    playback.setSpeechRate((float) input.optDouble("rate", 1.0));
                    return "Speed changed. " + playback.statusDescription();
                case "jump_to_chunk":
                    playback.jumpToChunk(input.optInt("chunk_index", 0));
                    return "Jumped. " + playback.statusDescription();
                case "get_playback_status":
                    return playback.statusDescription();
                case "get_document_outline":
                    return playback.outline();
                case "get_transcript_window": {
                    String window = playback.transcriptWindow(
                            input.optDouble("seconds_back", 30));
                    return window.isEmpty()
                            ? "Nothing has been read aloud yet."
                            : "Text spoken in that window: " + window;
                }

                // memory
                case "remember":
                    team.db.remember(input.optString("kind", "note"),
                            input.optString("content", ""), input.optString("tags", ""));
                    return "Saved.";
                case "recall": {
                    List<ContextDatabase.Memory> found =
                            team.db.recall(input.optString("query", ""), 6);
                    if (found.isEmpty()) {
                        return "No matching memories.";
                    }
                    StringBuilder sb = new StringBuilder();
                    for (ContextDatabase.Memory memory : found) {
                        sb.append("[").append(memory.kind).append("] ")
                                .append(memory.content).append('\n');
                    }
                    return sb.toString();
                }

                // spawning
                case "spawn_agent": {
                    String childName = input.optString("name", "worker");
                    String childTask = input.optString("task", "");
                    if (input.optBoolean("background", false)) {
                        return "Spawned background agent: "
                                + team.spawnBackground(childName, childTask, depth + 1);
                    }
                    return team.spawnAndWait(childName, childTask, depth + 1);
                }
                case "get_agent_result":
                    return team.getResult(input.optString("agent_id", ""));
                case "list_agents":
                    return team.listBackgroundAgents();

                // workbench
                case "write_file":
                    return team.workbench.writeFile(
                            input.optString("path", ""), input.optString("content", ""));
                case "read_file":
                    return team.workbench.readFile(input.optString("path", ""));
                case "list_files":
                    return team.workbench.listFiles();
                case "run_command":
                    return team.workbench.runCommand(input.optString("command", ""));

                // model fleet
                case "list_models":
                    return "Installed models:\n" + team.registry.describe()
                            + "\nCurrent routing:\n" + team.router.explainRouting()
                            + "\nScoreboard:\n" + team.db.scoreboard();
                case "sync_models":
                    return team.registry.syncFromManifest();
                case "record_model_feedback":
                    team.router.recordOutcome(input.optString("model", ""),
                            input.optString("task", "chat"),
                            input.optBoolean("success", true));
                    return "Feedback recorded.";

                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static JSONObject obj() {
        return new JSONObject();
    }

    private static JSONObject prop(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject tool(String toolName, String description, JSONObject properties)
            throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object");
        schema.put("properties", properties == null ? new JSONObject() : properties);
        return new JSONObject()
                .put("name", toolName)
                .put("description", description)
                .put("input_schema", schema);
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
