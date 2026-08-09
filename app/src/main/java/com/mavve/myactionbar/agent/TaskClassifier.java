package com.mavve.myactionbar.agent;

import java.util.Locale;

/**
 * Maps an utterance to a task category for the model router. Deliberately a
 * cheap keyword pass — it runs before any model is chosen, so it cannot
 * itself depend on a model. Miscategorization is not fatal: it only shifts
 * which scorecard the outcome lands on, and the learned scores self-correct.
 */
public final class TaskClassifier {

    private TaskClassifier() {
    }

    public static String classify(String utterance) {
        if (utterance == null) {
            return "chat";
        }
        String text = utterance.toLowerCase(Locale.ROOT);
        if (containsAny(text, "code", "coding", "build an app", "write a", "function",
                "compile", "test", "bug", "fix", "implement", "program", "script", "debug")) {
            return "coding";
        }
        if (containsAny(text, "research", "find out", "look up", "search", "investigate",
                "compare", "sources", "study")) {
            return "research";
        }
        if (containsAny(text, "plan", "schedule", "organize", "strategy", "steps",
                "roadmap")) {
            return "planning";
        }
        if (containsAny(text, "summarize", "summary", "tl;dr", "shorten", "recap")) {
            return "summarization";
        }
        if (containsAny(text, "read", "play", "pause", "rewind", "clarify", "louder",
                "faster", "slower", "chunk")) {
            return "voice_control";
        }
        return "chat";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
