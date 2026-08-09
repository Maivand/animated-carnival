# Kokoro TTS service

Jarvis's **cheap voice for long content**. Short spoken turns use the realtime
model; reading whole documents aloud is delegated here so it costs nothing per
minute. Kokoro is an 82M open TTS that runs on CPU and has natural British
voices (default `bm_george`, a good butler match).

## Protocol

```
GET  /health                      -> {"status":"ok","voice":"bm_george","rate":24000}

POST /tts   X-Kokoro-Token: <token>
{ "text": "…", "voice": "bm_george", "lang": "b", "speed": 1.0 }
-> raw little-endian PCM16 mono @ 24000 Hz
```

The app requests one document segment at a time and streams the returned PCM
straight into an AudioTrack.

## Run

```bash
cd backend/kokoro-tts
KOKORO_TOKEN=$(openssl rand -hex 24) docker compose up -d --build
curl -fsS localhost:8710/health
```

First `/tts` call downloads the model weights from Hugging Face (cached in
`./hf-cache`). It runs comfortably on a 2 vCPU / 2 GB box — fine alongside the
other services on the VPS.

Then in the app: gear → **Kokoro TTS URL** (`http://<vps>:8710/tts`) and
**Kokoro token**. Put HTTPS in front for anything beyond testing.

Voices: British `bm_george`, `bm_lewis`, `bf_emma`, `bf_isabella`; American
`am_adam`, `am_michael`, `af_heart`, and more — set `KOKORO_VOICE` or pass
`voice` per request.
