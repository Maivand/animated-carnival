package com.mavve.myactionbar.models;

import android.content.Context;
import android.content.SharedPreferences;

import com.mavve.myactionbar.llm.AnthropicClient;
import com.mavve.myactionbar.llm.LlmClient;
import com.mavve.myactionbar.llm.OpenAiCompatClient;
import com.mavve.myactionbar.memory.ContextDatabase;

import java.util.List;
import java.util.Locale;

/**
 * Picks the best installed model for each task category and keeps improving
 * the choice from real outcomes.
 *
 *   score(model, task) = 0.4 * manifest/seed prior + 0.6 * learned win rate
 *
 * Every completed agent run calls recordOutcome(), so if some model keeps
 * failing at coding while another keeps succeeding, the routing flips
 * automatically — no configuration, no app update. A small exploration rate
 * occasionally tries a non-top model so newly installed challengers can earn
 * evidence instead of starving.
 */
public class ModelRouter {

    public static class Pick {
        public final ModelSpec spec;
        public final LlmClient client;

        Pick(ModelSpec spec, LlmClient client) {
            this.spec = spec;
            this.client = client;
        }
    }

    private static final String PREFS = "voice_agent";
    private static final String PREF_ANTHROPIC_KEY = "anthropic_api_key";
    private static final String PREF_COMPAT_KEY = "compat_api_key";

    private static final double PRIOR_WEIGHT = 0.4;
    private static final double LEARNED_WEIGHT = 0.6;
    /** Roughly 1 in 12 requests explores a non-top model. */
    private static final int EXPLORE_ONE_IN = 12;

    private final ModelRegistry registry;
    private final ContextDatabase db;
    private final SharedPreferences prefs;
    private int requestCounter = 0;

    public ModelRouter(Context context, ModelRegistry registry, ContextDatabase db) {
        this.registry = registry;
        this.db = db;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Choose a model for the task category and build a ready client for it. */
    public synchronized Pick pick(String task) {
        List<ModelSpec> models = registry.all();
        ModelSpec best = null;
        ModelSpec secondBest = null;
        double bestScore = -1;
        double secondScore = -1;
        for (ModelSpec spec : models) {
            if (!isUsable(spec)) {
                continue;
            }
            double score = PRIOR_WEIGHT * spec.prior(task)
                    + LEARNED_WEIGHT * db.learnedScore(spec.id, task);
            if (score > bestScore) {
                secondBest = best;
                secondScore = bestScore;
                best = spec;
                bestScore = score;
            } else if (score > secondScore) {
                secondBest = spec;
                secondScore = score;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "No usable model: configure an API key first.");
        }
        requestCounter++;
        if (secondBest != null && requestCounter % EXPLORE_ONE_IN == 0) {
            best = secondBest; // exploration turn: give the runner-up a shot
        }
        return new Pick(best, buildClient(best));
    }

    public void recordOutcome(String modelId, String task, boolean success) {
        db.recordModelOutcome(modelId, task, success);
    }

    public String explainRouting() {
        StringBuilder sb = new StringBuilder();
        String[] tasks = {"coding", "research", "planning", "chat", "voice_control",
                "summarization"};
        for (String task : tasks) {
            ModelSpec top = null;
            double topScore = -1;
            for (ModelSpec spec : registry.all()) {
                if (!isUsable(spec)) {
                    continue;
                }
                double score = PRIOR_WEIGHT * spec.prior(task)
                        + LEARNED_WEIGHT * db.learnedScore(spec.id, task);
                if (score > topScore) {
                    top = spec;
                    topScore = score;
                }
            }
            if (top != null) {
                sb.append(task).append(" -> ").append(top.id)
                        .append(String.format(Locale.US, " (%.2f)", topScore)).append('\n');
            }
        }
        return sb.length() == 0 ? "No usable models configured." : sb.toString();
    }

    private boolean isUsable(ModelSpec spec) {
        if (ModelSpec.PROVIDER_ANTHROPIC.equals(spec.provider)) {
            return !prefs.getString(PREF_ANTHROPIC_KEY, "").isEmpty();
        }
        if (ModelSpec.PROVIDER_OPENAI_COMPAT.equals(spec.provider)) {
            return !spec.baseUrl.isEmpty()
                    && !prefs.getString(PREF_COMPAT_KEY, "").isEmpty();
        }
        return false;
    }

    private LlmClient buildClient(ModelSpec spec) {
        if (ModelSpec.PROVIDER_ANTHROPIC.equals(spec.provider)) {
            return new AnthropicClient(prefs.getString(PREF_ANTHROPIC_KEY, ""));
        }
        return new OpenAiCompatClient(spec.baseUrl, prefs.getString(PREF_COMPAT_KEY, ""));
    }
}
