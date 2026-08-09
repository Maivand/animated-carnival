package com.mavve.myactionbar.vision;

import com.mavve.myactionbar.voice.RealtimeVoiceSession;

/**
 * A tiny process-wide handle to the currently-active realtime voice session,
 * so the screen-vision overlay service (a separate component that can run over
 * other apps) can inject a captured screenshot into the ongoing conversation
 * without a full service/binder plumbing job.
 */
public final class RealtimeSessionHolder {

    private static volatile RealtimeVoiceSession current;

    private RealtimeSessionHolder() {
    }

    public static void set(RealtimeVoiceSession session) {
        current = session;
    }

    public static RealtimeVoiceSession get() {
        return current;
    }

    public static void clear(RealtimeVoiceSession session) {
        if (current == session) {
            current = null;
        }
    }
}
