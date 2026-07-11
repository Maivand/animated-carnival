"""YouTube audio collection for dialect- and slang-heavy Swedish speech.

Downloads audio-only streams with yt-dlp from a seed list of channels/searches
tagged by dialect region (configs/youtube_seeds.yaml). The output is raw audio
+ a sidecar metadata JSON per video; preprocess.py segments it and
pseudo_label.py transcribes it.

⚠️ Read docs/LEGAL.md before running this at scale. Only collect content you
have the right to use for training in your jurisdiction, respect robots/ToS,
and honor takedown requests. Videos with manual Swedish subtitles are
preferred (the subs become weak labels and reduce pseudo-label load).
"""

from __future__ import annotations

import json
import logging
import subprocess
from dataclasses import dataclass
from pathlib import Path

import yaml

log = logging.getLogger(__name__)


@dataclass
class Seed:
    url: str  # channel, playlist or search URL
    dialect: str  # region key, see eval/dialects.py
    tags: list  # e.g. ["slang", "youth"] or ["dialect", "interview"]
    max_videos: int = 50


def load_seeds(path: str | Path) -> list[Seed]:
    with Path(path).open(encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    return [Seed(**entry) for entry in raw["seeds"]]


def _ytdlp_cmd(seed: Seed, out_dir: Path) -> list[str]:
    return [
        "yt-dlp",
        "--extract-audio",
        "--audio-format", "wav",
        "--postprocessor-args", "ffmpeg:-ar 16000 -ac 1",
        "--max-downloads", str(seed.max_videos),
        "--match-filter", "duration > 60 & duration < 5400",
        "--write-info-json",
        "--write-subs", "--sub-langs", "sv",  # manual Swedish subs when present
        "--no-playlist-reverse",
        "--sleep-interval", "2", "--max-sleep-interval", "8",  # be polite
        "--download-archive", str(out_dir / "downloaded.txt"),  # resumable
        "-o", str(out_dir / "%(id)s.%(ext)s"),
        seed.url,
    ]


def collect(seeds_path: str | Path, out_root: str | Path) -> None:
    """Run collection for every seed; audio lands in out_root/<dialect>/."""
    seeds = load_seeds(seeds_path)
    out_root = Path(out_root)
    for seed in seeds:
        out_dir = out_root / seed.dialect
        out_dir.mkdir(parents=True, exist_ok=True)
        # Sidecar so downstream stages know the dialect/tags of each directory.
        meta = {"url": seed.url, "dialect": seed.dialect, "tags": seed.tags}
        (out_dir / "_seed_meta.json").write_text(json.dumps(meta, ensure_ascii=False))
        log.info("collecting %s (%s)", seed.url, seed.dialect)
        try:
            subprocess.run(_ytdlp_cmd(seed, out_dir), check=True)
        except subprocess.CalledProcessError as e:
            # yt-dlp exits 101 when --max-downloads is reached — that's success.
            if e.returncode != 101:
                log.warning("yt-dlp failed for %s (rc=%s); continuing", seed.url, e.returncode)
