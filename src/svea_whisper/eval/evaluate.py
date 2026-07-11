"""Evaluate a model on a manifest: WER/CER overall and per dialect region."""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from pathlib import Path

from ..data.manifest import read_manifest
from ..data.normalize_sv import normalize_eval

log = logging.getLogger(__name__)


def evaluate_manifest(
    model_path: str,
    manifest_path: str | Path,
    device: str = "auto",
    batch_size: int = 8,
    out_json: str | Path | None = None,
) -> dict:
    import jiwer
    import torch
    import transformers
    from transformers import pipeline

    if device == "auto":
        device = "cuda:0" if torch.cuda.is_available() else "cpu"

    dtype = torch.float16 if "cuda" in str(device) else torch.float32
    dtype_kwarg = "dtype" if int(transformers.__version__.split(".")[0]) >= 5 else "torch_dtype"
    asr = pipeline(
        "automatic-speech-recognition",
        model=model_path,
        device=device,
        **{dtype_kwarg: dtype},
    )
    generate_kwargs = {"language": "sv", "task": "transcribe"}

    utts = list(read_manifest(manifest_path))
    refs_by_dialect: dict[str, list] = defaultdict(list)
    hyps_by_dialect: dict[str, list] = defaultdict(list)
    lenient_refs_by_dialect: dict[str, list] = defaultdict(list)
    lenient_hyps_by_dialect: dict[str, list] = defaultdict(list)

    import soundfile as sf

    def load(path: str) -> dict:
        # Decode with soundfile so eval doesn't depend on an ffmpeg binary.
        audio, sr = sf.read(path, dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        return {"raw": audio, "sampling_rate": sr}

    for start in range(0, len(utts), batch_size):
        batch = utts[start : start + batch_size]
        outputs = asr([load(u.audio_path) for u in batch], generate_kwargs=generate_kwargs)
        for utt, out in zip(batch, outputs):
            ref, hyp = normalize_eval(utt.text), normalize_eval(out["text"])
            if not ref:
                continue
            refs_by_dialect[utt.dialect].append(ref)
            hyps_by_dialect[utt.dialect].append(hyp)
            # Lenient scoring: slang/standard spelling pairs count as equal —
            # the number that reflects what a (young) user actually experiences.
            lenient_refs_by_dialect[utt.dialect].append(normalize_eval(utt.text, lenient=True))
            lenient_hyps_by_dialect[utt.dialect].append(normalize_eval(out["text"], lenient=True))

    all_refs = [r for refs in refs_by_dialect.values() for r in refs]
    all_hyps = [h for hyps in hyps_by_dialect.values() for h in hyps]
    all_lrefs = [r for refs in lenient_refs_by_dialect.values() for r in refs]
    all_lhyps = [h for hyps in lenient_hyps_by_dialect.values() for h in hyps]
    report = {
        "model": str(model_path),
        "manifest": str(manifest_path),
        "utterances": len(all_refs),
        "wer": round(jiwer.wer(all_refs, all_hyps), 4),
        "wer_lenient": round(jiwer.wer(all_lrefs, all_lhyps), 4),
        "cer": round(jiwer.cer(all_refs, all_hyps), 4),
        "per_dialect": {
            dialect: {
                "utterances": len(refs),
                "wer": round(jiwer.wer(refs, hyps_by_dialect[dialect]), 4),
                "wer_lenient": round(
                    jiwer.wer(lenient_refs_by_dialect[dialect],
                              lenient_hyps_by_dialect[dialect]), 4),
                "cer": round(jiwer.cer(refs, hyps_by_dialect[dialect]), 4),
            }
            for dialect, refs in sorted(refs_by_dialect.items())
        },
    }
    if out_json:
        Path(out_json).parent.mkdir(parents=True, exist_ok=True)
        Path(out_json).write_text(json.dumps(report, indent=2, ensure_ascii=False))
    log.info("WER %.2f%% / CER %.2f%% on %d utterances",
             report["wer"] * 100, report["cer"] * 100, report["utterances"])
    return report
