"""Harvest human labels from manual YouTube subtitles.

yt-dlp downloads manual Swedish subs as ``<video_id>.sv.vtt`` next to the
audio. Cue timestamps let us cut the audio into utterances whose transcript
is HUMAN text, not a pseudo-label — the highest-quality slang/dialect data we
can get without paying annotators. Videos with subs are excluded from
pseudo-labeling downstream (pseudo_label skips segments that already have a
subs-derived manifest entry — handled in the pipeline by harvesting first).
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from pathlib import Path

from .manifest import Utterance, write_manifest
from .normalize_sv import normalize_train

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
MIN_CUE_S = 1.0
MAX_CUE_S = 28.0

_TIMESTAMP = re.compile(
    r"(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})"
)
_TAG = re.compile(r"<[^>]+>")


@dataclass
class Cue:
    start: float
    end: float
    text: str

    @property
    def duration(self) -> float:
        return self.end - self.start


def _to_seconds(h, m, s, ms) -> float:
    return int(h or 0) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000


def parse_vtt(content: str) -> list:
    """Parse WebVTT into cues. Handles tags, karaoke-style repeats and
    multi-line cue text."""
    cues: list[Cue] = []
    current: Cue | None = None
    for line in content.splitlines():
        m = _TIMESTAMP.search(line)
        if m:
            if current and current.text:
                cues.append(current)
            current = Cue(
                start=_to_seconds(*m.groups()[:4]),
                end=_to_seconds(*m.groups()[4:]),
                text="",
            )
        elif current is not None:
            text = normalize_train(_TAG.sub("", line))
            if text and not text.isdigit():
                current.text = f"{current.text} {text}".strip()
    if current and current.text:
        cues.append(current)

    # Karaoke/rollup subs repeat lines across cues — drop exact repeats and
    # cues fully contained in the previous cue's text.
    deduped: list[Cue] = []
    for cue in cues:
        if deduped and (cue.text == deduped[-1].text or cue.text in deduped[-1].text):
            deduped[-1].end = max(deduped[-1].end, cue.end)
            continue
        deduped.append(cue)
    return deduped


def merge_cues(cues: list, max_s: float = MAX_CUE_S, max_gap_s: float = 1.0) -> list:
    """Merge consecutive short cues into utterance-sized segments."""
    merged: list[Cue] = []
    for cue in cues:
        if (
            merged
            and cue.start - merged[-1].end <= max_gap_s
            and cue.end - merged[-1].start <= max_s
        ):
            merged[-1].text = f"{merged[-1].text} {cue.text}"
            merged[-1].end = cue.end
        else:
            merged.append(Cue(cue.start, cue.end, cue.text))
    return [c for c in merged if MIN_CUE_S <= c.duration <= max_s and c.text]


def harvest_directory(audio_dir: str | Path, out_manifest: str | Path,
                      out_audio_dir: str | Path) -> dict:
    """Cut subtitle-labeled utterances for every <id>.sv.vtt with a matching
    <id>.wav under audio_dir (recursive). Returns counts; also returns the set
    of video ids consumed so pseudo-labeling can skip them."""
    import json

    import soundfile as sf

    audio_dir, out_audio_dir = Path(audio_dir), Path(out_audio_dir)
    out_audio_dir.mkdir(parents=True, exist_ok=True)
    n_videos = 0
    harvested_ids = []

    def gen():
        nonlocal n_videos
        for vtt in sorted(audio_dir.rglob("*.sv.vtt")):
            video_id = vtt.name.removesuffix(".sv.vtt")
            wav = vtt.parent / f"{video_id}.wav"
            if not wav.exists():
                continue
            meta_file = vtt.parent / "_seed_meta.json"
            dialect = "unknown"
            if meta_file.exists():
                dialect = json.loads(meta_file.read_text()).get("dialect", "unknown")

            segments = merge_cues(parse_vtt(vtt.read_text(encoding="utf-8", errors="replace")))
            if not segments:
                continue
            n_videos += 1
            harvested_ids.append(video_id)
            audio, sr = sf.read(wav, dtype="float32")
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            for i, cue in enumerate(segments):
                lo, hi = int(cue.start * sr), min(int(cue.end * sr), len(audio))
                if hi - lo < MIN_CUE_S * sr:
                    continue
                seg_path = out_audio_dir / f"{video_id}_sub{i:04d}.wav"
                sf.write(seg_path, audio[lo:hi], sr)
                yield Utterance(
                    audio_path=str(seg_path),
                    text=cue.text,
                    duration=round((hi - lo) / sr, 3),
                    source="youtube_subs",
                    dialect=dialect,
                    pseudo_labeled=False,
                    label_confidence=1.0,
                )

    n = write_manifest(gen(), out_manifest)
    Path(out_manifest).with_suffix(".harvested_ids.json").write_text(
        __import__("json").dumps(harvested_ids)
    )
    log.info("harvested %d utterances from %d subtitled videos -> %s",
             n, n_videos, out_manifest)
    return {"utterances": n, "videos": n_videos}
