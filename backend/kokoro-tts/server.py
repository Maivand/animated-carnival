#!/usr/bin/env python3
"""Kokoro TTS service for Jarvis.

The cheap voice for LONG content. Jarvis's short spoken turns go through the
realtime model, but reading whole documents aloud would burn realtime tokens,
so the main agent delegates that to this on-box Kokoro service instead —
Kokoro is an 82M open TTS that runs on CPU and has natural British voices, a
good match for the butler persona.

Protocol:
    GET  /health                         -> {"status":"ok","voice":...}
    POST /tts   X-Kokoro-Token: <token>
    { "text": "…", "voice": "bm_george", "lang": "b", "speed": 1.0 }
    -> raw little-endian PCM16 mono @ 24000 Hz (audio/L16)

Returns raw PCM so the Android client can stream it straight into an
AudioTrack. First request downloads the model weights from Hugging Face.
"""

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from kokoro import KPipeline

TOKEN = os.environ.get("KOKORO_TOKEN", "")
PORT = int(os.environ.get("KOKORO_PORT", "8710"))
DEFAULT_VOICE = os.environ.get("KOKORO_VOICE", "bm_george")  # British male
DEFAULT_LANG = os.environ.get("KOKORO_LANG", "b")            # British English
MAX_TEXT = 8000

_pipelines = {}


def pipeline(lang):
    if lang not in _pipelines:
        _pipelines[lang] = KPipeline(lang_code=lang)
    return _pipelines[lang]


def synthesize(text, voice, lang, speed):
    pipe = pipeline(lang)
    out = []
    for _, _, audio in pipe(text, voice=voice, speed=speed, split_pattern=r"\n+"):
        arr = audio.numpy() if hasattr(audio, "numpy") else np.asarray(audio, dtype=np.float32)
        arr = np.clip(arr, -1.0, 1.0)
        out.append((arr * 32767.0).astype("<i2").tobytes())
    return b"".join(out)


class Handler(BaseHTTPRequestHandler):
    server_version = "JarvisKokoro/1.0"

    def _json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "ok", "voice": DEFAULT_VOICE, "rate": 24000})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if not TOKEN:
            self._json(503, {"error": "KOKORO_TOKEN not set"})
            return
        if self.headers.get("X-Kokoro-Token") != TOKEN:
            self._json(401, {"error": "bad or missing X-Kokoro-Token"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 2 * 1024 * 1024:
            self._json(413, {"error": "body missing or too large"})
            return
        try:
            req = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._json(400, {"error": "invalid JSON"})
            return

        text = (req.get("text") or "").strip()[:MAX_TEXT]
        if not text:
            self._json(400, {"error": "missing text"})
            return
        voice = req.get("voice") or DEFAULT_VOICE
        lang = req.get("lang") or DEFAULT_LANG
        try:
            speed = float(req.get("speed", 1.0))
        except (TypeError, ValueError):
            speed = 1.0

        try:
            pcm = synthesize(text, voice, lang, speed)
        except Exception as error:  # noqa: BLE001 - report synth failures to client
            self._json(500, {"error": "synthesis failed: %s" % error})
            return

        self.send_response(200)
        self.send_header("Content-Type", "audio/L16; rate=24000; channels=1")
        self.send_header("X-Sample-Rate", "24000")
        self.send_header("Content-Length", str(len(pcm)))
        self.end_headers()
        self.wfile.write(pcm)

    def log_message(self, fmt, *args):
        pass


def main():
    if not TOKEN:
        print("WARNING: KOKORO_TOKEN not set; all synth requests will be refused.")
    print("Jarvis Kokoro TTS listening on :%d (voice %s)" % (PORT, DEFAULT_VOICE))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
