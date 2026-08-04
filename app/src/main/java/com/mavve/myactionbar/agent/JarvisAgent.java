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
    private static final int SOLUTION_SNIPPETS = 3;

    private final AgentTeam team;
    private final String name;
    private final int depth;
    private final PlaybackEngine playback; // null for sub-agents
    private final MediaSurface media;      // null for sub-agents
    /** Persistent conversation, so this agent can be messaged again later. */
    private final JSONArray messages = new JSONArray();

    JarvisAgent(AgentTeam team, String name, int depth, PlaybackEngine playback,
                MediaSurface media) {
        this.team = team;
        this.name = name;
        this.depth = depth;
        this.playback = playback;
        this.media = media;
    }

    /** Run the loop for a fresh task and return the final answer text. */
    public String ask(String task) {
        return converse(task);
    }

    /**
     * Continue this agent's existing conversation with a follow-up or
     * correction — full context from earlier turns is retained.
     */
    public String continueConversation(String message) {
        return converse(message);
    }

    /** Number of messages accumulated; used to retire long conversations. */
    public int conversationLength() {
        return messages.length();
    }

    private String converse(String userText) {
        String category = TaskClassifier.classify(userText);
        ModelRouter.Pick pick;
        try {
            pick = team.router.pick(category);
        } catch (Exception e) {
            return "No model available: " + e.getMessage();
        }
        team.log(name + " [" + category + "] -> " + pick.spec.id);
        try {
            messages.put(new JSONObject().put("role", "user").put("content", userText));
            String answer = runLoop(pick, userText);
            team.router.recordOutcome(pick.spec.id, category, true);
            return answer;
        } catch (Exception e) {
            team.router.recordOutcome(pick.spec.id, category, false);
            team.log(name + " failed: " + e.getMessage());
            return "Agent " + name + " hit an error: " + e.getMessage();
        }
    }

    private String runLoop(ModelRouter.Pick pick, String task) throws Exception {
        JSONArray tools = buildTools();
        String system = buildSystemPrompt(task);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (team.isCancelled()) {
                return "Stopped by user.";
            }
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
            sb.append(team.prompts.get(PromptStore.MAIN));
        } else {
            sb.append(team.prompts.get(PromptStore.WORKER)
                    .replace("{{name}}", name)
                    .replace("{{depth}}", String.valueOf(depth)));
        }

        String behavior = team.prompts.behavior();
        if (!behavior.isEmpty()) {
            sb.append("\n\nStanding user instructions (always follow):\n").append(behavior);
        }

        List<ContextDatabase.Memory> solutions =
                team.brain.recallSolutions(task, SOLUTION_SNIPPETS);
        if (!solutions.isEmpty()) {
            sb.append("\n\nPast solutions that worked (reuse before re-deriving):\n");
            for (ContextDatabase.Memory solution : solutions) {
                sb.append("- ").append(solution.content).append('\n');
            }
        }

        List<ContextDatabase.Memory> memories = team.brain.recall(task, MEMORY_SNIPPETS);
        if (!memories.isEmpty()) {
            sb.append("\nPossibly relevant memories:\n");
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

        // Media tools: main voice agent only.
        if (media != null) {
            tools.put(tool("show_image", "Display an image on the in-app screen.",
                    obj().put("url", prop("string", "Direct https image URL."))
                            .put("caption", prop("string", "Short caption to show."))));
            tools.put(tool("play_video", "Play a video on the in-app screen.",
                    obj().put("url", prop("string",
                            "Direct https video URL (e.g. .mp4/.webm stream)."))
                            .put("caption", prop("string", "Short caption to show."))));
            tools.put(tool("show_webpage",
                    "Open a web page in the in-app viewer (also works for image "
                            + "galleries and embedded players).",
                    obj().put("url", prop("string", "https URL to open."))));
            tools.put(tool("hide_media", "Hide the in-app media panel.", null));
        }

        // Memory / RAG tools: everyone.
        tools.put(tool("remember", "Save a durable fact, preference, or result to memory.",
                obj().put("content", prop("string", "What to remember."))
                        .put("kind", prop("string", "fact | preference | result | note"))
                        .put("tags", prop("string", "Comma-separated tags for retrieval."))));
        tools.put(tool("recall", "Semantic search over long-term memory.",
                obj().put("query", prop("string", "What to look for."))));
        tools.put(tool("search_documents",
                "RAG retrieval: fetch the most relevant chunks from all indexed "
                        + "documents for a query. Use this to answer questions about "
                        + "loaded documents.",
                obj().put("query", prop("string", "The question or topic."))
                        .put("limit", prop("integer", "Max chunks to return (default 5)."))));
        tools.put(tool("list_documents", "List indexed documents in the second brain.", null));

        // Spawning tools: until the depth limit.
        if (depth < AgentTeam.MAX_DEPTH) {
            tools.put(tool("spawn_agent",
                    "Spawn a sub-agent for a subtask. Sub-agents share memory and the "
                            + "workspace and can spawn their own sub-agents. The result comes "
                            + "back tagged with an [agent-id] you can use with message_agent. "
                            + "Use background true for independent work you will collect later.",
                    obj().put("name", prop("string", "Short role name, e.g. researcher."))
                            .put("task", prop("string", "Complete, self-contained instructions."))
                            .put("background", prop("boolean",
                                    "false (default): wait and get the result now. "
                                            + "true: get an agent id to poll."))));
            tools.put(tool("message_agent",
                    "Send a follow-up or correction to an agent you spawned earlier. It "
                            + "keeps its full conversation context and continues working.",
                    obj().put("agent_id", prop("string", "Id returned by spawn_agent."))
                            .put("message", prop("string",
                                    "The follow-up, correction, or new sub-question."))));
            tools.put(tool("get_agent_result", "Fetch a background agent's result by id.",
                    obj().put("agent_id", prop("string", "Id returned by spawn_agent."))));
            tools.put(tool("list_agents", "List background agents and their state.", null));
        }

        // Learning tools: everyone.
        tools.put(tool("save_solution",
                "Store a VERIFIED solution to a problem in procedural memory so future "
                        + "runs reuse it instead of re-deriving it. Only after confirming "
                        + "it actually works.",
                obj().put("problem", prop("string", "The problem, briefly."))
                        .put("solution", prop("string",
                                "The exact fix/command/approach that worked."))));
        if (depth == 0) {
            tools.put(tool("set_behavior",
                    "Record a standing user instruction about how Jarvis should behave "
                            + "from now on (persists across sessions).",
                    obj().put("instruction", prop("string",
                            "The standing instruction, e.g. 'always answer in Swedish'."))));
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

                // media (offered only when media != null)
                case "show_image":
                    media.showImage(input.optString("url", ""),
                            input.optString("caption", ""));
                    return "Image shown on screen.";
                case "play_video":
                    media.playVideo(input.optString("url", ""),
                            input.optString("caption", ""));
                    return "Video playing on screen.";
                case "show_webpage":
                    media.showPage(input.optString("url", ""));
                    return "Page opened on screen.";
                case "hide_media":
                    media.hideMedia();
                    return "Media panel hidden.";

                // memory / RAG
                case "remember":
                    team.brain.remember(input.optString("kind", "note"),
                            input.optString("content", ""), input.optString("tags", ""));
                    return "Saved.";
                case "recall": {
                    List<ContextDatabase.Memory> found =
                            team.brain.recall(input.optString("query", ""), 6);
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
                case "search_documents": {
                    List<ContextDatabase.ChunkHit> hits = team.brain.searchDocuments(
                            input.optString("query", ""), input.optInt("limit", 5));
                    if (hits.isEmpty()) {
                        return "No relevant chunks found. "
                                + team.brain.listDocuments();
                    }
                    StringBuilder sb = new StringBuilder();
                    for (ContextDatabase.ChunkHit hit : hits) {
                        sb.append("[").append(hit.docTitle).append(" #")
                                .append(hit.chunkIndex).append("] ")
                                .append(hit.content).append('\n');
                    }
                    return sb.toString();
                }
                case "list_documents":
                    return team.brain.listDocuments();

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
                case "message_agent":
                    return team.messageAgent(input.optString("agent_id", ""),
                            input.optString("message", ""));
                case "get_agent_result":
                    return team.getResult(input.optString("agent_id", ""));
                case "list_agents":
                    return team.listBackgroundAgents();

                // learning
                case "save_solution":
                    team.brain.saveSolution(input.optString("problem", ""),
                            input.optString("solution", ""));
                    return "Solution stored for future reuse.";
                case "set_behavior":
                    team.prompts.addBehavior(input.optString("instruction", ""));
                    return "Standing instruction recorded.";

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
