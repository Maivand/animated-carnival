package com.mavve.myactionbar.agent;

import android.content.Context;

import com.mavve.myactionbar.dev.CodeWorkbench;
import com.mavve.myactionbar.memory.ContextDatabase;
import com.mavve.myactionbar.memory.SecondBrain;
import com.mavve.myactionbar.models.ModelRegistry;
import com.mavve.myactionbar.models.ModelRouter;
import com.mavve.myactionbar.remote.AgentZeroClient;
import com.mavve.myactionbar.voice.PlaybackEngine;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The agent swarm. Holds the shared services (memory, router, workbench,
 * playback) and spawns JarvisAgents. Agents spawn sub-agents through this
 * class too — recursion is bounded by MAX_DEPTH and a total-agent budget per
 * user request, so "agents that spawn agents" cannot fork-bomb the phone.
 *
 * Depth 0 is the main voice agent (the only one with playback tools). Deeper
 * agents are workers: researchers, coders, summarizers.
 */
public class AgentTeam {

    public interface Logger {
        void log(String line);
    }

    public static final int MAX_DEPTH = 3;
    private static final int MAX_AGENTS_PER_REQUEST = 8;
    private static final long BACKGROUND_RESULT_WAIT_MS = 250;

    final ContextDatabase db;
    final SecondBrain brain;
    final ModelRegistry registry;
    final ModelRouter router;
    final CodeWorkbench workbench;
    final PromptStore prompts;
    final AgentZeroClient agentZero;
    final Logger logger;
    private PlaybackEngine playback;
    private MediaSurface media;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Future<String>> background = new ConcurrentHashMap<>();
    /** Every spawned agent, kept alive so it can be messaged again. */
    private final Map<String, JarvisAgent> liveAgents = new ConcurrentHashMap<>();
    private final AtomicInteger agentBudget = new AtomicInteger(0);
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** The persistent depth-0 agent: conversation context survives turns. */
    private JarvisAgent mainAgent;

    /** Retire the main agent's conversation once it grows past this. */
    private static final int MAIN_CONVERSATION_LIMIT = 40;

    public AgentTeam(Context context, Logger logger) {
        Context app = context.getApplicationContext();
        this.db = new ContextDatabase(app);
        this.brain = new SecondBrain(app, db);
        this.registry = new ModelRegistry(app);
        this.router = new ModelRouter(app, registry, db);
        this.workbench = new CodeWorkbench(app);
        this.prompts = new PromptStore(app);
        this.agentZero = new AgentZeroClient(app);
        this.logger = logger;
    }

    public void attachPlayback(PlaybackEngine engine) {
        this.playback = engine;
    }

    public void attachMedia(MediaSurface surface) {
        this.media = surface;
    }

    public ContextDatabase database() {
        return db;
    }

    public SecondBrain brain() {
        return brain;
    }

    public ModelRegistry models() {
        return registry;
    }

    public ModelRouter modelRouter() {
        return router;
    }

    /**
     * Entry point for a fresh user utterance. Resets the budget and the
     * cancel flag, then continues the persistent main agent's conversation —
     * so "actually, make that Swedish" works across voice turns. The
     * conversation is retired and restarted once it grows too long (memory
     * and solutions carry the context forward).
     */
    public String runMainAgent(String utterance) {
        agentBudget.set(0);
        cancelled.set(false);
        if (liveAgents.size() > 64) {
            for (String id : liveAgents.keySet()) {
                Future<String> pending = background.get(id);
                if (pending == null || pending.isDone()) {
                    liveAgents.remove(id);
                }
            }
        }
        synchronized (this) {
            if (mainAgent == null
                    || mainAgent.conversationLength() > MAIN_CONVERSATION_LIMIT) {
                mainAgent = new JarvisAgent(this, "jarvis", 0, playback, media);
            }
        }
        return mainAgent.continueConversation(utterance);
    }

    /** Ask every running agent to stop at its next round boundary. */
    public void cancelAll() {
        cancelled.set(true);
        log("stop requested");
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Synchronous spawn: runs the sub-agent and returns its tagged answer. */
    public String spawnAndWait(String name, String task, int depth) {
        if (!admit(name, depth)) {
            return budgetRefusal(depth);
        }
        String id = "agent-" + idCounter.incrementAndGet() + "-" + name;
        JarvisAgent agent = new JarvisAgent(this, name, depth, null, null);
        liveAgents.put(id, agent);
        return "[" + id + "] " + agent.ask(task);
    }

    /** Continue a previously spawned agent's conversation. */
    public String messageAgent(String agentId, String message) {
        JarvisAgent agent = liveAgents.get(agentId);
        if (agent == null) {
            return "Unknown agent id: " + agentId;
        }
        Future<String> pending = background.get(agentId);
        if (pending != null && !pending.isDone()) {
            return "Agent " + agentId + " is still working; wait for its result first.";
        }
        return agent.continueConversation(message);
    }

    /** Asynchronous spawn: returns an agent id to poll with getResult(). */
    public String spawnBackground(final String name, final String task, final int depth) {
        if (!admit(name, depth)) {
            return budgetRefusal(depth);
        }
        final String id = "agent-" + idCounter.incrementAndGet() + "-" + name;
        final JarvisAgent agent = new JarvisAgent(this, name, depth, null, null);
        liveAgents.put(id, agent);
        Future<String> future = pool.submit(new Callable<String>() {
            @Override
            public String call() {
                return agent.ask(task);
            }
        });
        background.put(id, future);
        return id;
    }

    public String getResult(String agentId) {
        Future<String> future = background.get(agentId);
        if (future == null) {
            return "Unknown agent id: " + agentId;
        }
        try {
            String result = future.get(BACKGROUND_RESULT_WAIT_MS, TimeUnit.MILLISECONDS);
            background.remove(agentId);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            return "Agent " + agentId + " is still working.";
        } catch (Exception e) {
            background.remove(agentId);
            return "Agent " + agentId + " failed: " + e.getMessage();
        }
    }

    public String listBackgroundAgents() {
        if (background.isEmpty()) {
            return "No background agents.";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Future<String>> entry : background.entrySet()) {
            sb.append(entry.getKey()).append(": ")
                    .append(entry.getValue().isDone() ? "done" : "running").append('\n');
        }
        return sb.toString();
    }

    // ---- realtime voice bridge -------------------------------------------

    /**
     * Function tools exposed to the realtime voice model. Instant playback
     * control plus one delegation tool that runs the full agent stack.
     * Shape matches the OpenAI Realtime API (type/function name/parameters).
     */
    public org.json.JSONArray realtimeTools() {
        org.json.JSONArray tools = new org.json.JSONArray();
        try {
            tools.put(rtTool("load_sample",
                    "Load the built-in sample research document so it can be read aloud.",
                    null));
            tools.put(rtTool("read_document",
                    "Fetch the start (or current position) of the loaded document as text "
                            + "for YOU to read aloud verbatim in your own voice. Returns one "
                            + "segment; call continue_reading for the next.",
                    prop("from", "string", "\"beginning\" or \"current\"")));
            tools.put(rtTool("continue_reading",
                    "Continue reading the document from where it left off.", null));
            tools.put(rtTool("rewind_reading",
                    "Go back to an earlier part of the document.", null));
            tools.put(rtTool("pause_reading", "Pause the document reading.", null));
            tools.put(rtTool("resume_reading", "Resume the paused document reading.", null));
            tools.put(rtTool("stop_reading", "Stop reading the document entirely.", null));
            tools.put(rtTool("ask_jarvis_agent",
                    "Delegate any non-trivial request (research, questions about loaded "
                            + "documents, memory, coding, showing media, backend delegation) "
                            + "to the full Jarvis agent. Returns text to speak to the user.",
                    prop("request", "string", "The user's request, in full.")));
        } catch (Exception ignored) {
        }
        return tools;
    }

    private static final int READ_BATCH_CHUNKS = 5;
    private int readCursor = 0;

    /**
     * Execute a realtime tool call. In live voice there is ONE voice — the
     * realtime model — so reading returns TEXT for the model to speak rather
     * than driving the on-device TTS (which would be a second, overlapping
     * voice that also feeds back into the mic).
     */
    public String executeRealtimeTool(String name, org.json.JSONObject input) {
        if (input == null) {
            input = new org.json.JSONObject();
        }
        try {
            java.util.List<String> chunks = playback != null
                    ? playback.getChunks() : java.util.Collections.<String>emptyList();
            switch (name) {
                case "read_document":
                    if (chunks.isEmpty()) {
                        return "NO_DOCUMENT: nothing is loaded. Ask the user to say 'load "
                                + "the sample', or to paste a document in settings.";
                    }
                    if (!"current".equals(input.optString("from", "beginning"))) {
                        readCursor = 0;
                    }
                    return readSegment(chunks);
                case "continue_reading":
                    if (chunks.isEmpty()) return "NO_DOCUMENT: nothing is loaded.";
                    return readSegment(chunks);
                case "rewind_reading":
                    if (chunks.isEmpty()) return "NO_DOCUMENT: nothing is loaded.";
                    readCursor = Math.max(0, readCursor - 2 * READ_BATCH_CHUNKS);
                    return readSegment(chunks);
                case "ask_jarvis_agent":
                    return runMainAgent(input.optString("request", ""));
                default:
                    return "Unknown tool: " + name;
            }
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    /** Next segment of the document as verbatim text for the model to read. */
    private String readSegment(java.util.List<String> chunks) {
        if (readCursor >= chunks.size()) {
            return "END_OF_DOCUMENT: you have reached the end; tell the user so.";
        }
        int end = Math.min(chunks.size(), readCursor + READ_BATCH_CHUNKS);
        StringBuilder sb = new StringBuilder();
        for (int i = readCursor; i < end; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(chunks.get(i));
        }
        readCursor = end;
        return "READ_ALOUD — read the following to the user verbatim in your own voice, "
                + "then stop and await (offer to continue): " + sb;
    }

    private static org.json.JSONObject rtTool(String name, String description,
                                              org.json.JSONObject properties) throws Exception {
        org.json.JSONObject params = new org.json.JSONObject().put("type", "object");
        params.put("properties", properties == null ? new org.json.JSONObject() : properties);
        return new org.json.JSONObject()
                .put("type", "function")
                .put("name", name)
                .put("description", description)
                .put("parameters", params);
    }

    private static org.json.JSONObject prop(String key, String type, String description)
            throws Exception {
        return new org.json.JSONObject().put(key,
                new org.json.JSONObject().put("type", type).put("description", description));
    }

    void log(String line) {
        if (logger != null) {
            logger.log(line);
        }
    }

    private boolean admit(String name, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (agentBudget.incrementAndGet() > MAX_AGENTS_PER_REQUEST) {
            agentBudget.decrementAndGet();
            return false;
        }
        log("spawn depth=" + depth + " name=" + name);
        return true;
    }

    private String budgetRefusal(int depth) {
        return depth > MAX_DEPTH
                ? "Refused: maximum agent depth (" + MAX_DEPTH + ") reached."
                : "Refused: agent budget for this request is exhausted.";
    }
}
