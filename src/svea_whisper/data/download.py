"""Download open datasets and emit JSONL manifests.

Streams from Hugging Face, writes 16 kHz mono WAVs + manifest per source.
Heavy imports are function-local so the rest of the package works without
the training stack installed.
"""

from __future__ import annotations

import logging
from pathlib import Path

from .manifest import Utterance, write_manifest
from .normalize_sv import normalize_train
from .sources import SOURCES

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
MIN_DURATION_S = 0.5
MAX_DURATION_S = 30.0  # Whisper context window


def build_manifest_for_source(
    source_key: str,
    out_dir: str | Path,
    split: str = "train",
    max_hours: float | None = None,
) -> Path:
    """Download one dataset split and write audio + manifest under out_dir."""
    import soundfile as sf
    from datasets import Audio, load_dataset

    src = SOURCES[source_key]
    out_dir = Path(out_dir)
    audio_dir = out_dir / "audio" / src.key / split
    audio_dir.mkdir(parents=True, exist_ok=True)

    ds = load_dataset(src.hf_id, src.hf_config, split=split, streaming=True)
    ds = ds.cast_column(src.audio_column, Audio(sampling_rate=SAMPLE_RATE))

    budget_s = (max_hours or float("inf")) * 3600
    kept_s = 0.0

    def gen():
        nonlocal kept_s
        for i, row in enumerate(ds):
            audio = row[src.audio_column]
            duration = len(audio["array"]) / audio["sampling_rate"]
            text = normalize_train(str(row[src.text_column]))
            if not text or not MIN_DURATION_S <= duration <= MAX_DURATION_S:
                continue
            if kept_s >= budget_s:
                return
            wav_path = audio_dir / f"{src.key}_{split}_{i:08d}.wav"
            sf.write(wav_path, audio["array"], SAMPLE_RATE)
            kept_s += duration
            yield Utterance(
                audio_path=str(wav_path),
                text=text,
                duration=round(duration, 3),
                source=src.key,
                speaker_id=str(row.get("client_id", row.get("speaker_id", ""))),
            )

    manifest_path = out_dir / f"{src.key}.{split}.jsonl"
    n = write_manifest(gen(), manifest_path)
    log.info("%s/%s: %d utterances, %.1f h -> %s", src.key, split, n, kept_s / 3600, manifest_path)
    return manifest_path


def build_all(out_dir: str | Path, sources: list[str] | None = None,
              split: str = "train", max_hours_per_source: float | None = None) -> list[Path]:
    paths = []
    for key in sources or list(SOURCES):
        try:
            paths.append(build_manifest_for_source(key, out_dir, split, max_hours_per_source))
        except Exception:
            log.exception("failed to build %s (auth needed? see sources.py); continuing", key)
    return paths
