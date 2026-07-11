"""Real-WER A/B: MoE ensemble vs plain generalist on a manifest.

This is the experiment that decides whether dialect experts + arbitration
ship: it runs the actual MoETranscriber over dialect-tagged audio and reports
strict/lenient WER per dialect for both systems, plus router/arbitration
diagnostics (which model won, how often re-routing fired).
"""

from __future__ import annotations

import json
import logging
from collections import Counter, defaultdict
from pathlib import Path

from ..data.manifest import read_manifest
from ..data.normalize_sv import normalize_eval

log = logging.getLogger(__name__)


def evaluate_moe(
    manifest_path: str | Path,
    generalist_path: str,
    expert_paths: dict,
    router_dir: str,
    device: str = "auto",
    out_json: str | Path | None = None,
) -> dict:
    import jiwer
    import soundfile as sf
    import torch

    from ..moe.ensemble import MoETranscriber
    from ..moe.router import DialectRouter

    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"

    router = DialectRouter(model_dir=router_dir, device=device)
    moe = MoETranscriber(generalist_path, expert_paths, router, device=device)

    refs = defaultdict(list)
    moe_hyps = defaultdict(list)
    gen_hyps = defaultdict(list)
    winners = Counter()
    reroutes = 0

    utts = list(read_manifest(manifest_path))
    for utt in utts:
        audio, sr = sf.read(utt.audio_path, dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        result = moe.transcribe(audio, sr)
        baseline = moe.backends["generalist"].transcribe(audio, sr)

        ref = normalize_eval(utt.text, lenient=True)
        if not ref:
            continue
        refs[utt.dialect].append(ref)
        moe_hyps[utt.dialect].append(normalize_eval(result["text"], lenient=True))
        gen_hyps[utt.dialect].append(normalize_eval(baseline.text, lenient=True))
        winners[result["model"]] += 1
        reroutes += result["diagnostics"].get("reroutes", 0)

    def wer_over(hyps_by_dialect) -> tuple:
        all_r = [r for rs in refs.values() for r in rs]
        all_h = [h for hs in hyps_by_dialect.values() for h in hs]
        overall = round(jiwer.wer(all_r, all_h), 4)
        per = {d: round(jiwer.wer(refs[d], hyps_by_dialect[d]), 4) for d in sorted(refs)}
        return overall, per

    moe_wer, moe_per = wer_over(moe_hyps)
    gen_wer, gen_per = wer_over(gen_hyps)
    report = {
        "manifest": str(manifest_path),
        "utterances": sum(len(v) for v in refs.values()),
        "generalist": {"wer_lenient": gen_wer, "per_dialect": gen_per},
        "moe": {"wer_lenient": moe_wer, "per_dialect": moe_per},
        "relative_improvement": round(1 - moe_wer / gen_wer, 4) if gen_wer else 0.0,
        "winner_counts": dict(winners),
        "total_reroutes": reroutes,
    }
    if out_json:
        Path(out_json).parent.mkdir(parents=True, exist_ok=True)
        Path(out_json).write_text(json.dumps(report, indent=2, ensure_ascii=False))
    log.info("MoE %.2f%% vs generalist %.2f%% lenient WER (%+.1f%% relative)",
             moe_wer * 100, gen_wer * 100, report["relative_improvement"] * 100)
    return report
