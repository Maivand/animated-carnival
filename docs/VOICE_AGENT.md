# Voice Agent with Playback-Control Tool Calling

This feature adds a Claude-style voice agent to the app whose defining trick is
that **playback is a set of tools, not generated text**. The agent never reads a
document "through the model" — it tool-calls an on-device playback engine that
reads local text with Android TextToSpeech. That is what makes commands like
*"go back 10 seconds"* or *"read all 100 pages"* both possible and nearly free
in tokens.

Open it from the main screen's overflow menu → **Voice agent**.

## How a voice command flows

```
🎤 speech  ──►  VoiceCommandRouter (on-device regex)
                   │ matched: pause / resume / back N sec / forward N sec /
                   │          read it all / faster / slower / start over
                   │           └─► PlaybackEngine     ← 0 API tokens
                   │
                   └─ not matched ──► ClaudeAgent (Messages API + tools)
                                        │  tool_use: rewind_playback{seconds:10}
                                        │  tool_use: get_transcript_window{seconds_back:30}
                                        │  tool_use: read_document{from:"beginning"}
                                        └─► PlaybackEngine, then a 1–2 sentence
                                            spoken reply
```

## The components

| Component | File | Role |
|---|---|---|
| DocumentChunker | `voice/DocumentChunker.java` | Splits any document into ~280-char sentence chunks |
| PlaybackEngine | `voice/PlaybackEngine.java` | TTS reader with a timed history of everything spoken |
| VoiceCommandRouter | `voice/VoiceCommandRouter.java` | Free on-device handling of common commands (English + some Swedish) |
| ClaudeAgent | `voice/ClaudeAgent.java` | Claude Messages API client with the playback tool loop |
| VoiceAgentActivity | `VoiceAgentActivity.java` | UI: load document, talk button, manual controls |

## Why this saves tokens

1. **Reading is local.** "Read all information from the 100 pages of research"
   becomes a single `read_document` tool call (a few hundred tokens round
   trip). The 100 pages are chunked and streamed through the device's TTS like
   a podcast — the model never sees or generates that text.
2. **Common commands never hit the API.** "Pause", "go back 10 seconds",
   "faster" are matched by regex on-device. Zero tokens, zero latency.
3. **Clarification uses a window, not the document.** "Clarify what you said
   30 seconds ago" makes the model call `get_transcript_window(seconds_back=30)`,
   which returns only the few sentences actually spoken in that window. The
   model answers from that tiny excerpt instead of re-ingesting the document.

## How rewind works

The engine records when each chunk started and finished speaking. To rewind
10 seconds it walks that history backwards, summing real spoken durations until
it has covered 10 seconds, then restarts playback from that chunk. Chunks are
sentence-sized, so seeking is accurate to a sentence or two. Skipping forward
estimates chunk duration from the measured average.

## The agent's tools

`read_document`, `pause_playback`, `resume_playback`, `rewind_playback`,
`forward_playback`, `set_speech_rate`, `jump_to_chunk`, `get_playback_status`,
`get_document_outline`, `get_transcript_window`.

The system prompt forbids the model from reading the document aloud itself and
keeps replies to one or two spoken sentences.

## Setup

1. Open **Voice agent** from the menu.
2. Tap **Set Claude API key** and paste an Anthropic API key (stored in
   SharedPreferences on the device only — fine for a demo, use a backend proxy
   for anything real).
3. **Load sample** (a bundled research summary) or paste your own text and tap
   **Load pasted text**.
4. Tap **Read the whole document**, or the 🎤 button and just say what you want.

Things to try saying:

- "Read it all" — starts the podcast-style read-through (handled on-device)
- "Go back 10 seconds" / "skip forward 30 seconds" (on-device)
- "Pause" / "continue" / "faster" (on-device)
- "Clarify what you said 30 seconds ago" (agent → `get_transcript_window`)
- "Jump to the part about caffeine" (agent → `get_document_outline` + `jump_to_chunk`)

## Notes

- The model is set in `ClaudeAgent.MODEL` (`claude-sonnet-5`); swap in
  `claude-haiku-4-5-20251001` for lower latency and cost.
- Speech input uses the system recognizer dialog (`ACTION_RECOGNIZE_SPEECH`),
  so no RECORD_AUDIO permission is needed; reading is paused while listening so
  the recognizer doesn't hear the TTS voice.
- Only the `INTERNET` permission was added.
