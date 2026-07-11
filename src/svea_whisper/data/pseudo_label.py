"""Pseudo-label unlabeled (YouTube) segments with KB-Whisper.

Transcribes each VAD segment and keeps only confident outputs, using average
token log-probability plus heuristics against Whisper's known failure modes
(repetition loops, empty/hallucinated text). Confidence is recorded in the
manifest so training can weight or re-filter pseudo-labels later.
"""

from __future__ import annotations

import json
import logging
import math
from pathlib import Path

from .manifest import Utterance, write_manifest
from .normalize_sv import normalize_train

log = logging.getLogger(__name__)

DEFAULT_MODEL = "KBLab/kb-whisper-large"
MIN_AVG_LOGPROB = -0.7  # ≈ 0.5 mean token probability
MAX_COMPRESSION_RATIO = 2.4  # above this = repetition loop


def _looks_degenerate(text: str) -> bool:
    if len(text) < 2:
        return True
    words = text.split()
    if len(words) >= 6 and len(set(words)) / len(words) < 0.4:  # heavy repetition
        return True
    return False


def transcribe_directory(
    audio_dir: str | Path,
    out_manifest: str | Path,
    model_id: str = DEFAULT_MODEL,
    device: str = "auto",
) -> dict:
    """Pseudo-label all segment WAVs under audio_dir (recursive).

    Dialect is taken from each directory's _seed_meta.json when present.
    Returns {"kept": n, "dropped": n}.
    """
    import torch
    import transformers
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32
    dtype_kwarg = "dtype" if int(transformers.__version__.split(".")[0]) >= 5 else "torch_dtype"

    processor = AutoProcessor.from_pretrained(model_id)
    model = AutoModelForSpeechSeq2Seq.from_pretrained(model_id, **{dtype_kwarg: dtype}).to(device)
    model.eval()

    audio_dir = Path(audio_dir)
    kept, dropped = 0, 0

    def gen():
        nonlocal kept, dropped
        import soundfile as sf

        for wav in sorted(audio_dir.rglob("*_seg*.wav")):
            meta_file = wav.parent / "_seed_meta.json"
            dialect = "unknown"
            if meta_file.exists():
                dialect = json.loads(meta_file.read_text()).get("dialect", "unknown")

            audio, sr = sf.read(wav, dtype="float32")
            inputs = processor(audio, sampling_rate=sr, return_tensors="pt")
            features = inputs.input_features.to(device, dtype=dtype)
            with torch.no_grad():
                out = model.generate(
                    features,
                    language="sv",
                    task="transcribe",
                    output_scores=True,
                    return_dict_in_generate=True,
                )
            text = normalize_train(
                processor.batch_decode(out.sequences, skip_special_tokens=True)[0]
            )
            scores = torch.stack(out.scores, dim=1).log_softmax(-1)
            token_ids = out.sequences[:, -scores.shape[1]:]
            avg_logprob = float(
                scores.gather(2, token_ids.unsqueeze(-1)).squeeze(-1).mean()
            )

            if avg_logprob < MIN_AVG_LOGPROB or _looks_degenerate(text):
                dropped += 1
                continue
            kept += 1
            yield Utterance(
                audio_path=str(wav),
                text=text,
                duration=round(len(audio) / sr, 3),
                source="youtube",
                dialect=dialect,
                pseudo_labeled=True,
                label_confidence=round(min(1.0, math.exp(avg_logprob)), 4),
            )

    write_manifest(gen(), out_manifest)
    log.info("pseudo-labeling done: kept=%d dropped=%d -> %s", kept, dropped, out_manifest)
    return {"kept": kept, "dropped": dropped}
