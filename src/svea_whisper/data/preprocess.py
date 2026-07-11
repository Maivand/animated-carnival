"""Segment long-form audio into Whisper-sized utterances with Silero VAD."""

from __future__ import annotations

import logging
from pathlib import Path

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
MAX_SEGMENT_S = 28.0  # leave headroom under Whisper's 30 s window
MIN_SEGMENT_S = 1.0


def _load_vad():
    import torch

    model, utils = torch.hub.load("snakers4/silero-vad", "silero_vad", trust_repo=True)
    get_speech_timestamps = utils[0]
    return model, get_speech_timestamps


def segment_file(wav_path: str | Path, out_dir: str | Path, vad=None) -> list:
    """Split one 16 kHz mono WAV into speech segments. Returns segment records
    as (path, duration) tuples."""
    import soundfile as sf
    import torch

    wav_path, out_dir = Path(wav_path), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    model, get_speech_timestamps = vad or _load_vad()

    audio, sr = sf.read(wav_path, dtype="float32")
    if sr != SAMPLE_RATE:
        raise ValueError(f"{wav_path}: expected {SAMPLE_RATE} Hz, got {sr} (resample in yt-dlp)")
    if audio.ndim > 1:
        audio = audio.mean(axis=1)

    stamps = get_speech_timestamps(
        torch.from_numpy(audio), model, sampling_rate=SAMPLE_RATE,
        max_speech_duration_s=MAX_SEGMENT_S, min_speech_duration_ms=int(MIN_SEGMENT_S * 1000),
        speech_pad_ms=150,
    )
    segments = []
    for i, ts in enumerate(stamps):
        chunk = audio[ts["start"] : ts["end"]]
        duration = len(chunk) / SAMPLE_RATE
        seg_path = out_dir / f"{wav_path.stem}_seg{i:04d}.wav"
        sf.write(seg_path, chunk, SAMPLE_RATE)
        segments.append((str(seg_path), round(duration, 3)))
    log.info("%s -> %d segments", wav_path.name, len(segments))
    return segments


def segment_directory(in_dir: str | Path, out_dir: str | Path) -> list:
    """Segment every WAV in in_dir (non-recursive; segments go to out_dir)."""
    vad = _load_vad()
    all_segments = []
    for wav in sorted(Path(in_dir).glob("*.wav")):
        all_segments.extend(segment_file(wav, out_dir, vad=vad))
    return all_segments
