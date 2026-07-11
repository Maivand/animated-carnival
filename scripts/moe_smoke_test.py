#!/usr/bin/env python3
"""Offline smoke test of the real MoE ensemble: two fixture models (generalist
+ one 'expert'), a router with an untrained head, language gate, dual decode,
arbitration and diagnostics — all on synthetic audio, no network needed.
"""

from __future__ import annotations

import math
import struct
import sys
import tempfile
import wave
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))

SR = 16_000


def make_wav(path: Path, seconds: float, freq: float) -> None:
    frames = b"".join(
        struct.pack("<h", int(0.3 * 32767 * math.sin(2 * math.pi * freq * t / SR)))
        for t in range(int(seconds * SR))
    )
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(frames)


def main() -> int:
    import soundfile as sf

    from svea_whisper.moe.ensemble import MoETranscriber
    from svea_whisper.moe.router import DialectRouter
    from svea_whisper.testing import build_tiny_model

    tmp = Path(tempfile.mkdtemp(prefix="svea_moe_smoke_"))
    print(f"[moe-smoke] workspace: {tmp}")

    generalist = build_tiny_model(tmp / "generalist")
    expert = build_tiny_model(tmp / "expert-fororts")
    print("[moe-smoke] built generalist + expert fixture models")

    router = DialectRouter(model_dir=generalist, device="cpu")
    moe = MoETranscriber(
        generalist_path=generalist,
        expert_paths={"fororts": expert},
        router=router,
        device="cpu",
        reroute_threshold=-999.0,  # random models are always low-confidence;
        # disable rerouting so the test is deterministic
    )

    wav = tmp / "utt.wav"
    make_wav(wav, seconds=2.0, freq=330.0)
    audio, sr = sf.read(wav, dtype="float32")

    result = moe.transcribe(audio, sr)
    print(f"[moe-smoke] result: {result}")

    diag = result["diagnostics"]
    assert "language" in diag and "language_prob" in diag
    assert result["model"] in {"generalist", "fororts"}
    # Untrained router head -> route() returns [("unknown", 1.0)]; plan must
    # still fall back to the generalist rather than crash.
    assert "generalist" in diag["decodes"]
    assert diag["confidences"], "confidence diagnostics missing"

    # Now exercise the sv path with a real dual decode by faking the router.
    router.route = lambda a, s: [("fororts", 0.9), ("sveamal", 0.1)]
    router.detect_language = lambda a, s: ("sv", 0.99)
    result = moe.transcribe(audio, sr)
    print(f"[moe-smoke] dual-decode result: {result}")
    assert set(result["diagnostics"]["decodes"]) == {"generalist", "fororts"}

    print("[moe-smoke] PASS — router, language gate, dual decode and "
          "arbitration all run end to end")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
