"""Build the training mix according to source weights.

DEFAULT_MIX_WEIGHTS says what fraction of training *hours* each source should
contribute. Given the available manifests, the largest total T is chosen such
that no source is asked for more hours than it has; each source is then
subsampled (deterministically) to its target share. Sources present in the
manifests but absent from the weights are dropped with a warning.
"""

from __future__ import annotations

import logging
import random
from collections import defaultdict
from pathlib import Path

from .manifest import Utterance, read_manifest, write_manifest
from .sources import DEFAULT_MIX_WEIGHTS

log = logging.getLogger(__name__)


def mix_manifests(
    inputs: list,
    out_path: str | Path,
    weights: dict | None = None,
    seed: int = 42,
) -> dict:
    """Returns {"hours_total": h, "hours_by_source": {...}, "utterances": n}."""
    weights = weights or DEFAULT_MIX_WEIGHTS
    # youtube_subs are human-labeled youtube audio: count them toward the
    # youtube share (and prefer them — they sort before pseudo-labels below).
    alias = {"youtube_subs": "youtube"}

    by_source: dict[str, list] = defaultdict(list)
    for path in inputs:
        for utt in read_manifest(path):
            key = alias.get(utt.source, utt.source)
            if key not in weights:
                continue
            by_source[key].append(utt)

    missing = [s for s in by_source if s not in weights]
    if missing:
        log.warning("sources without mix weight dropped: %s", missing)

    hours = {s: sum(u.duration for u in utts) / 3600 for s, utts in by_source.items()}
    if not hours:
        raise ValueError("no utterances matched the mix weights")

    # Renormalize weights over the sources we actually have.
    active = {s: weights[s] for s in hours}
    total_w = sum(active.values())
    active = {s: w / total_w for s, w in active.items()}
    # Largest feasible total: no source over-asked.
    total_hours = min(hours[s] / w for s, w in active.items())

    rng = random.Random(seed)
    picked: list[Utterance] = []
    hours_by_source = {}
    for source, utts in sorted(by_source.items()):
        target_s = active[source] * total_hours * 3600
        # Human labels first (label_confidence 1.0, pseudo_labeled False),
        # then highest-confidence pseudo-labels; random tiebreak.
        utts = sorted(utts, key=lambda u: (u.pseudo_labeled, -u.label_confidence,
                                           rng.random()))
        acc = 0.0
        for utt in utts:
            if acc >= target_s:
                break
            picked.append(utt)
            acc += utt.duration
        hours_by_source[source] = round(acc / 3600, 2)

    rng.shuffle(picked)
    n = write_manifest(picked, out_path)
    stats = {
        "hours_total": round(sum(hours_by_source.values()), 2),
        "hours_by_source": hours_by_source,
        "utterances": n,
    }
    log.info("mix -> %s: %s", out_path, stats)
    return stats
