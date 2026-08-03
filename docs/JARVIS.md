# JARVIS — The Ultimate Voice Agent

This repo is now a single-purpose app: a Jarvis-class voice agent. This
document is the blueprint: the full feature vision, what already exists in the
ecosystem to build each piece, what didn't exist and had to be invented here,
and what is actually implemented in the code today.

---

## 1. The Jarvis feature list

The novel features that make Jarvis *Jarvis*, not just a chatbot with a mic:

1. **Always-listening voice presence** — wake word, barge-in (interrupt it
   mid-sentence), full-duplex conversation while it works.
2. **Playback as a first-class medium** — reads any amount of content aloud
   like a podcast; rewind/skip/clarify by voice; "read all 100 pages" costs
   almost nothing because reading is local. *(implemented)*
3. **Persistent memory** — remembers people, preferences, decisions, and past
   work across sessions; recalls before it asks. *(implemented — context DB)*
4. **Agent swarms** — the main agent spawns specialists (researcher, coder,
   critic), and those specialists can spawn their own helpers, bounded by
   depth and budget. *(implemented)*
5. **Self-coding** — writes, runs, and fixes real software in a workspace
   with a test loop, like Claude Code. *(implemented, execution via sandbox)*
6. **A model fleet, not a model** — many LLMs installed side by side; the
   best one for each kind of task gets that task. *(implemented)*
7. **Constant self-optimization** — every outcome updates a scorecard; if a
   model keeps winning at coding, it gets the coding. *(implemented)*
8. **Auto-install of new models** — when a better model ships, it appears in
   a manifest feed, gets installed, earns evidence, and takes over the tasks
   it is best at. No app update. *(implemented for API models)*
9. **Proactivity** — notices things (calendar, messages, deadlines) and
   speaks up first. *(future — needs notification listeners + triggers)*
10. **Device and home control** — lights, locks, thermostats, phone settings.
    *(future — Matter/Home Assistant integration)*
11. **Multimodal senses** — camera understanding, screen understanding, sound
    events. *(future — on-device vision models)*
12. **Emotional intelligence** — hears stress or urgency in the voice and
    adapts pace and verbosity. *(future — prosody models)*

## 2. Framework survey — is everything invented?

Mostly yes; the glue is what was missing. What exists and what we use or can
use per subsystem:

| Need | Exists today | Status here |
|---|---|---|
| LLM APIs + tool calling | Anthropic Messages API, OpenAI-compatible APIs everywhere | **Used** — both client types implemented |
| Many-provider access | OpenRouter, LiteLLM, local llama.cpp / Ollama servers | **Used** — one `OpenAiCompatClient` covers all of them |
| Agent frameworks | Claude Agent SDK, LangGraph, CrewAI (none run on Android/Java) | **Invented** — `JarvisAgent` + `AgentTeam`, a minimal recursive agent runtime in plain Java |
| Tool ecosystems | MCP (Model Context Protocol) | Future — an MCP client would plug external tool servers into `JarvisAgent` |
| Speech-to-text | Android SpeechRecognizer (used), whisper.cpp, Vosk, sherpa-onnx for offline/wake-word | **Used** (system recognizer); offline STT is a drop-in upgrade |
| Text-to-speech | Android TTS (used), Piper/Coqui for premium local voices | **Used** |
| Memory / vector DBs | SQLite (used), ObjectBox Vector, sqlite-vec for embeddings | **Used** — SQLite with keyword+recency; embedding upgrade path documented below |
| Code execution sandboxes | Docker/Firecracker runners, Judge0, self-hosted runners | **Invented (protocol)** — a tiny HTTP sandbox contract (below); phones can't safely run arbitrary builds locally |
| Model routing | LiteLLM router, OpenRouter auto — but both route by price/availability, not by *learned per-task skill* | **Invented** — outcome-scored router (priors + win-rate learning + exploration) |
| Model auto-discovery | Nothing standard exists for "a better model came out, start using it" | **Invented** — the model manifest + auto-install + evidence-based takeover |

So: the individual technologies all exist. The three things that did not exist
as off-the-shelf pieces — and are invented in this codebase — are:

1. **The outcome-scored model router** (`ModelRouter` + `model_scores` table):
   `score = 0.4·prior + 0.6·learned win rate`, with a 1-in-12 exploration turn
   so newly installed models can earn evidence instead of starving.
2. **The model manifest protocol** (`ModelRegistry.syncFromManifest`): a JSON
   feed of model specs; anything new is auto-installed, anything known gets
   updated priors. Learned scores always outrank manifest claims, so a
   manifest can nominate a challenger but not crown it.
3. **The phone-side recursive agent runtime** (`AgentTeam`/`JarvisAgent`):
   spawning, depth limits, agent budgets, shared memory/workspace, all in
   dependency-free Java that runs on a 2015 phone.

## 3. Architecture

```
                    ┌────────────────────────────────────────────┐
 🎤 speech ──►      │ VoiceCommandRouter (regex, on-device, free)│──► PlaybackEngine ──► 🔊
      │             └────────────────────────────────────────────┘        ▲
      │ not a playback command                                            │ playback tools
      ▼                                                                   │
 ┌──────────────── AgentTeam ───────────────────────────────────────────────────┐
 │  jarvis (depth 0) ── spawn ──► researcher (1) ── spawn ──► fact-checker (2)  │
 │      │                             coder (1)                                 │
 │      │ every agent picks its model per task:                                 │
 │      ▼                                                                       │
 │  TaskClassifier ─► ModelRouter ─► LlmClient (Anthropic │ OpenAI-compat)      │
 │      ▲                  │                                                    │
 │      │           outcomes recorded                                           │
 │  ┌───┴──────────────────▼──────┐   ┌──────────────┐   ┌───────────────────┐  │
 │  │ ContextDatabase (SQLite)    │   │ CodeWorkbench│   │ ModelRegistry     │  │
 │  │ memories + model scorecards │   │ files+sandbox│   │ seeds + manifest  │  │
 │  └─────────────────────────────┘   └──────────────┘   └───────────────────┘  │
 └──────────────────────────────────────────────────────────────────────────────┘
```

Every agent, at every depth, has: memory tools (`remember`/`recall`),
workspace tools (`write_file`/`read_file`/`list_files`/`run_command`),
fleet tools (`list_models`/`sync_models`/`record_model_feedback`), and — until
the depth limit — `spawn_agent`/`get_agent_result`/`list_agents`. Only the
depth-0 voice agent gets the playback tools.

## 4. The invented protocols

### Sandbox runner (write-and-test like Claude Code)

`run_command` POSTs the whole workspace to the configured sandbox URL:

```json
POST <sandbox_url>
{ "command": "pytest -q",
  "files": [ {"path": "src/main.py", "content": "..."} ] }

→ { "exit_code": 0, "stdout": "...", "stderr": "..." }
```

Any ~30-line Flask/Express server in a Docker container satisfies this. The
agent loop then reads stderr, fixes the files, and runs again — the same
loop Claude Code uses, with the phone as the brain and the sandbox as the
hands.

### Model manifest (auto-install)

A URL returning:

```json
[ { "id": "some-new-model", "provider": "openai-compat",
    "base_url": "https://openrouter.ai/api/v1",
    "priors": { "coding": 0.93, "default": 0.8 },
    "note": "new SOTA coder, released 2026-08" } ]
```

`sync_models` (a button, and a tool the agent can call on itself) merges the
feed: new ids are installed, known ids get updated priors. The router starts
giving the newcomer exploration turns immediately; if it actually wins, the
scorecard promotes it. **Point this only at a feed you trust** — it decides
where your prompts get sent.

## 5. Honest limitations & next steps

- **No build was run here** — this environment has no Android SDK. The code
  is javac-syntax-checked only; expect to iterate once in Android Studio.
- Memory retrieval is keyword+recency. Upgrade path: store an embedding per
  memory (any embedding API, or sherpa-onnx locally) in a BLOB column and
  cosine-rank; the `recall` tool contract doesn't change.
- Latency: routing itself is instant, but deep agent trees mean serial API
  round-trips. Background spawning (`background: true`) is the mitigation.
- Wake word / barge-in needs a foreground service + offline keyword spotting
  (Porcupine or sherpa-onnx); the current mic button is the placeholder.
- The old lifecycle/ListView demo code from the course days is gone.
