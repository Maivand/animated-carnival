package com.mavve.myactionbar.agent;

import android.content.Context;

import com.mavve.myactionbar.dev.CodeWorkbench;
import com.mavve.myactionbar.memory.ContextDatabase;
import com.mavve.myactionbar.models.ModelRegistry;
import com.mavve.myactionbar.models.ModelRouter;
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
    final ModelRegistry registry;
    final ModelRouter router;
    final CodeWorkbench workbench;
    final Logger logger;
    private PlaybackEngine playback;

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Future<String>> background = new ConcurrentHashMap<>();
    private final AtomicInteger agentBudget = new AtomicInteger(0);
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public AgentTeam(Context context, Logger logger) {
        Context app = context.getApplicationContext();
        this.db = new ContextDatabase(app);
        this.registry = new ModelRegistry(app);
        this.router = new ModelRouter(app, registry, db);
        this.workbench = new CodeWorkbench(app);
        this.logger = logger;
    }

    public void attachPlayback(PlaybackEngine engine) {
        this.playback = engine;
    }

    public ContextDatabase database() {
        return db;
    }

    public ModelRegistry models() {
        return registry;
    }

    public ModelRouter modelRouter() {
        return router;
    }

    /** Entry point for a fresh user request: resets the agent budget. */
    public String runMainAgent(String utterance) {
        agentBudget.set(0);
        return spawnAndWait("jarvis", utterance, 0);
    }

    /** Synchronous spawn: runs the sub-agent and returns its final answer. */
    public String spawnAndWait(String name, String task, int depth) {
        if (!admit(name, depth)) {
            return budgetRefusal(depth);
        }
        JarvisAgent agent = new JarvisAgent(this, name, depth,
                depth == 0 ? playback : null);
        return agent.ask(task);
    }

    /** Asynchronous spawn: returns an agent id to poll with getResult(). */
    public String spawnBackground(final String name, final String task, final int depth) {
        if (!admit(name, depth)) {
            return budgetRefusal(depth);
        }
        final String id = "agent-" + idCounter.incrementAndGet() + "-" + name;
        Future<String> future = pool.submit(new Callable<String>() {
            @Override
            public String call() {
                JarvisAgent agent = new JarvisAgent(AgentTeam.this, name, depth, null);
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
