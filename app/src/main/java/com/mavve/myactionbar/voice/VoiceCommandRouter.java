package com.mavve.myactionbar.voice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast local routing of the most common playback commands. Anything matched
 * here is executed directly against the PlaybackEngine and never leaves the
 * device — zero API tokens spent. Only commands this router does not
 * understand (e.g. "clarify what you meant about the sample size") are
 * forwarded to the Claude agent, which then uses tool calls to control the
 * same engine.
 *
 * Supports English plus a few Swedish phrasings.
 */
public final class VoiceCommandRouter {

    private static final Pattern PAUSE = Pattern.compile(
            "\\b(pause|stop( reading| talking)?|hold on|be quiet|pausa|stopp|vänta)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESUME = Pattern.compile(
            "\\b(resume|continue|keep (going|reading)|go on|fortsätt)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern READ_ALL = Pattern.compile(
            "\\b(read (it |me )?(all|everything)|read the (whole|full|entire)|start reading|läs allt)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern START_OVER = Pattern.compile(
            "\\b(start (over|again)|from the (beginning|top)|börja om)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REWIND_KEYWORD = Pattern.compile(
            "\\b(back|backwards|rewind|tillbaka)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FORWARD_KEYWORD = Pattern.compile(
            "\\b(forward|ahead|framåt)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d+)\\s*(seconds?|secs?|minutes?|mins?|sekunder?|minuter?)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FASTER = Pattern.compile(
            "\\b(faster|speed up|snabbare)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SLOWER = Pattern.compile(
            "\\b(slower|slow down|långsammare)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Utterances that look like questions must reach the agent even if they
     * also contain a seek word ("clarify what you said 30 seconds back").
     */
    private static final Pattern NEEDS_AGENT = Pattern.compile(
            "\\b(clarify|explain|what|why|who|when|where|how|which|summari[zs]e|tell me|mean[st]?|repeat in|förklara|vad|varför|hur)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final double DEFAULT_SEEK_SECONDS = 10;

    private VoiceCommandRouter() {
    }

    /**
     * Try to handle the utterance locally against the engine.
     *
     * @return true if the command was recognized and executed on-device;
     *         false if it should be sent to the Claude agent instead.
     */
    public static boolean tryHandle(String utterance, PlaybackEngine engine) {
        if (utterance == null || utterance.trim().isEmpty()) {
            return false;
        }
        String text = utterance.trim();

        if (NEEDS_AGENT.matcher(text).find()) {
            return false;
        }
        if (READ_ALL.matcher(text).find() || START_OVER.matcher(text).find()) {
            engine.readFromBeginning();
            return true;
        }
        // "back"/"forward" are checked before pause/resume so that phrases like
        // "go back and keep going" seek instead of resuming.
        if (REWIND_KEYWORD.matcher(text).find()) {
            engine.rewindSeconds(extractSeconds(text));
            return true;
        }
        if (FORWARD_KEYWORD.matcher(text).find()) {
            engine.forwardSeconds(extractSeconds(text));
            return true;
        }
        if (PAUSE.matcher(text).find()) {
            engine.pause();
            return true;
        }
        if (RESUME.matcher(text).find()) {
            engine.resume();
            return true;
        }
        if (FASTER.matcher(text).find()) {
            engine.setSpeechRate(engine.getSpeechRate() + 0.25f);
            return true;
        }
        if (SLOWER.matcher(text).find()) {
            engine.setSpeechRate(engine.getSpeechRate() - 0.25f);
            return true;
        }
        return false;
    }

    private static double extractSeconds(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        if (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            if (unit != null && unit.toLowerCase().startsWith("min")) {
                value *= 60;
            }
            return value;
        }
        return DEFAULT_SEEK_SECONDS;
    }
}
