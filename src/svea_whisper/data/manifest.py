"""Manifest schema and I/O.

Every stage of the pipeline speaks one format: JSONL manifests where each line
is one utterance. Audio stays on disk; manifests only carry paths + metadata.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Iterator


@dataclass
class Utterance:
    """One audio segment with (possibly pseudo-) transcript and metadata."""

    audio_path: str
    text: str
    duration: float  # seconds
    source: str  # e.g. "common_voice", "fleurs", "youtube"
    dialect: str = "unknown"  # region key, see eval/dialects.py
    speaker_id: str = ""
    # Pseudo-label bookkeeping (empty/1.0 for human transcripts)
    pseudo_labeled: bool = False
    label_confidence: float = 1.0
    extra: dict = field(default_factory=dict)

    def validate(self) -> None:
        if not self.audio_path:
            raise ValueError("audio_path is required")
        if self.duration <= 0:
            raise ValueError(f"non-positive duration for {self.audio_path}")
        if not 0.0 <= self.label_confidence <= 1.0:
            raise ValueError(f"label_confidence out of range for {self.audio_path}")


def write_manifest(utterances: Iterable[Utterance], path: str | Path) -> int:
    """Write utterances to a JSONL manifest. Returns number written."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with path.open("w", encoding="utf-8") as f:
        for utt in utterances:
            utt.validate()
            f.write(json.dumps(asdict(utt), ensure_ascii=False) + "\n")
            n += 1
    return n


def read_manifest(path: str | Path) -> Iterator[Utterance]:
    with Path(path).open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield Utterance(**json.loads(line))


def manifest_stats(path: str | Path) -> dict:
    """Hours, utterance count and dialect/source breakdown of a manifest."""
    total_s = 0.0
    n = 0
    by_dialect: dict[str, float] = {}
    by_source: dict[str, float] = {}
    for utt in read_manifest(path):
        total_s += utt.duration
        n += 1
        by_dialect[utt.dialect] = by_dialect.get(utt.dialect, 0.0) + utt.duration
        by_source[utt.source] = by_source.get(utt.source, 0.0) + utt.duration
    return {
        "utterances": n,
        "hours": round(total_s / 3600, 2),
        "hours_by_dialect": {k: round(v / 3600, 2) for k, v in sorted(by_dialect.items())},
        "hours_by_source": {k: round(v / 3600, 2) for k, v in sorted(by_source.items())},
    }
