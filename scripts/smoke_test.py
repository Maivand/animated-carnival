#!/usr/bin/env python3
"""End-to-end CPU smoke test: synthetic audio -> manifest -> 2 training steps
on kb-whisper-tiny -> eval. Validates the whole train/eval path without a GPU.

Usage: python scripts/smoke_test.py [--base-model KBLab/kb-whisper-tiny]
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import tempfile
import wave
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))

from svea_whisper.data.manifest import Utterance, write_manifest  # noqa: E402

SR = 16_000
SENTENCES = [
    "hej och välkommen till podden",
    "asså bror det var helt sjukt",
    "vi ses i malmö på lördag",
    "jag käkade köttbullar igår",
]


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
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-model", default="KBLab/kb-whisper-tiny")
    args = parser.parse_args()

    tmp = Path(tempfile.mkdtemp(prefix="svea_smoke_"))
    print(f"[smoke] workspace: {tmp}")

    # 1. Synthetic dataset (sine waves — we test plumbing, not quality)
    utts = []
    for i, text in enumerate(SENTENCES):
        wav = tmp / f"utt{i}.wav"
        make_wav(wav, seconds=2.0 + 0.5 * i, freq=220.0 * (i + 1))
        utts.append(Utterance(str(wav), text, 2.0 + 0.5 * i, "smoke",
                              dialect="fororts" if i == 1 else "sveamal"))
    train_manifest = tmp / "train.jsonl"
    write_manifest(utts, train_manifest)
    print(f"[smoke] wrote {len(utts)} synthetic utterances")

    # 2. Two training steps on CPU
    config = {
        "base_model": args.base_model,
        "train_manifests": [str(train_manifest)],
        "eval_manifest": str(train_manifest),
        "output_dir": str(tmp / "run"),
        "learning_rate": 1e-5,
        "warmup_steps": 1,
        "max_steps": 2,
        "per_device_batch_size": 2,
        "gradient_accumulation": 1,
        "eval_steps": 2,
        "save_steps": 2,
        "fp16": False,
        "gradient_checkpointing": False,
    }
    config_path = tmp / "config.yaml"
    import yaml

    config_path.write_text(yaml.safe_dump(config))

    from svea_whisper.train.finetune import train

    model_dir = train(config_path)
    print(f"[smoke] training finished, model at {model_dir}")

    # 3. Eval loop (WER will be terrible on sine waves — it just has to run)
    from svea_whisper.eval.evaluate import evaluate_manifest

    report = evaluate_manifest(str(model_dir), train_manifest,
                               out_json=tmp / "eval.json")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    assert report["utterances"] == len(utts)
    assert "fororts" in report["per_dialect"]
    print("[smoke] PASS — train + eval pipeline works end to end")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
