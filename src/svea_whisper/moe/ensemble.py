"""Mixture-of-experts transcriber: router + generalist + dialect experts.

Decode flow per utterance:
1. Language gate — Whisper-native lang-ID on the router's tiny model; non-sv
   audio short-circuits to the generalist (KB-Whisper handles sv best but
   still degrades gracefully on other languages).
2. Dialect routing — ranked expert candidates.
3. Dual decode — generalist AND top expert in the first wave (configurable).
4. Arbitration — length-normalized avg logprob picks the winner.
5. Re-route — if the winner still looks unreliable, decode the router's
   next-ranked expert and re-arbitrate ("rearrange and find the optimal one").
"""

from __future__ import annotations

import logging
from pathlib import Path

from .arbitration import Candidate, arbitrate, next_expert, plan_decodes, should_reroute
from .router import DialectRouter

log = logging.getLogger(__name__)


class WhisperBackend:
    """One loaded Whisper model that decodes with a confidence estimate."""

    def __init__(self, model_path: str | Path, key: str, device: str = "cpu"):
        from transformers import WhisperForConditionalGeneration, WhisperProcessor

        self.key = key
        self.device = device
        self.model = WhisperForConditionalGeneration.from_pretrained(str(model_path)).to(device)
        self.model.eval()
        self.processor = WhisperProcessor.from_pretrained(str(model_path))

    def transcribe(self, audio, sr: int, language: str = "sv") -> Candidate:
        import torch

        features = self.processor(
            audio, sampling_rate=sr, return_tensors="pt"
        ).input_features.to(self.device)
        with torch.no_grad():
            out = self.model.generate(
                features, language=language, task="transcribe",
                output_scores=True, return_dict_in_generate=True,
            )
        text = self.processor.batch_decode(out.sequences, skip_special_tokens=True)[0].strip()
        scores = torch.stack(out.scores, dim=1).log_softmax(-1)
        token_ids = out.sequences[:, -scores.shape[1]:]
        avg_logprob = float(scores.gather(2, token_ids.unsqueeze(-1)).squeeze(-1).mean())
        return Candidate(text=text, avg_logprob=avg_logprob, model_key=self.key)


class MoETranscriber:
    def __init__(
        self,
        generalist_path: str | Path,
        expert_paths: dict,  # dialect key -> model path
        router: DialectRouter,
        device: str = "cpu",
        always_dual: bool = True,
        expert_bias: float = 0.0,
        reroute_threshold: float = -0.6,
        max_reroutes: int = 1,
        sv_prob_gate: float = 0.5,
    ):
        self.router = router
        self.backends = {"generalist": WhisperBackend(generalist_path, "generalist", device)}
        for dialect, path in expert_paths.items():
            self.backends[dialect] = WhisperBackend(path, dialect, device)
        self.experts = set(expert_paths)
        self.always_dual = always_dual
        self.expert_bias = expert_bias
        self.reroute_threshold = reroute_threshold
        self.max_reroutes = max_reroutes
        self.sv_prob_gate = sv_prob_gate

    def transcribe(self, audio, sr: int) -> dict:
        language, lang_prob = self.router.detect_language(audio, sr)
        diagnostics = {"language": language, "language_prob": round(lang_prob, 3)}

        if language != "sv" and lang_prob >= self.sv_prob_gate:
            # Foreign speech: dialect experts don't apply.
            best = self.backends["generalist"].transcribe(audio, sr, language=language)
            diagnostics.update(decodes=[best.model_key], routed=[])
            return self._result(best, diagnostics)

        ranked = self.router.route(audio, sr)
        diagnostics["routed"] = [(d, round(p, 3)) for d, p in ranked[:3]]

        plan = plan_decodes(ranked, self.experts, ranked[0][1], self.always_dual)
        candidates = [self.backends[key].transcribe(audio, sr) for key in plan]
        best = arbitrate(candidates, self.expert_bias)

        reroutes = 0
        tried = set(plan)
        while should_reroute(best, self.reroute_threshold) and reroutes < self.max_reroutes:
            fallback = next_expert(ranked, self.experts, tried)
            if fallback is None:
                break
            tried.add(fallback)
            candidates.append(self.backends[fallback].transcribe(audio, sr))
            best = arbitrate(candidates, self.expert_bias)
            reroutes += 1

        diagnostics.update(
            decodes=[c.model_key for c in candidates],
            confidences={c.model_key: round(c.avg_logprob, 3) for c in candidates},
            reroutes=reroutes,
        )
        return self._result(best, diagnostics)

    @staticmethod
    def _result(best: Candidate, diagnostics: dict) -> dict:
        return {
            "text": best.text,
            "model": best.model_key,
            "avg_logprob": round(best.avg_logprob, 4),
            "diagnostics": diagnostics,
        }
