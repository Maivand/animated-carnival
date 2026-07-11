"""Dialect router: fast language + dialect identification for expert routing.

Language ID comes free from Whisper itself (its detect-language pass on the
first 30 s costs one encoder forward + one decoder step with a tiny model).
Dialect ID is a small classifier head on mean-pooled encoder states of the
same tiny model, trained on our dialect-tagged manifests — so routing adds
only tiny-encoder latency (~tens of ms on CPU) before the real decode.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger(__name__)

ROUTER_BASE = "KBLab/kb-whisper-tiny"


class DialectRouter:
    def __init__(self, model_dir: str | Path | None = None, base_model: str = ROUTER_BASE,
                 device: str = "cpu"):
        import torch
        from transformers import WhisperForConditionalGeneration, WhisperProcessor

        self.device = device
        # A usable local checkpoint dir has config.json; anything else falls
        # back to the hub base model.
        source = (
            str(model_dir)
            if model_dir and (Path(model_dir) / "config.json").exists()
            else base_model
        )
        self.base = WhisperForConditionalGeneration.from_pretrained(source).to(device).eval()
        self.processor = WhisperProcessor.from_pretrained(source)
        self.labels: list = []
        self.head = None
        if model_dir and (Path(model_dir) / "router_head.pt").exists():
            state = torch.load(Path(model_dir) / "router_head.pt", map_location=device)
            self.labels = state["labels"]
            self.head = torch.nn.Linear(state["dim"], len(self.labels)).to(device)
            self.head.load_state_dict(state["head"])
            self.head.eval()

    # ---------- shared feature extraction ----------

    def _encode(self, audio, sr: int):
        import torch

        features = self.processor(
            audio, sampling_rate=sr, return_tensors="pt"
        ).input_features.to(self.device)
        with torch.no_grad():
            hidden = self.base.model.encoder(features).last_hidden_state
        return hidden.mean(dim=1)  # (1, d_model)

    # ---------- language ID ----------

    def detect_language(self, audio, sr: int) -> tuple:
        """(language_code, probability) via Whisper's native lang-ID step."""
        import torch

        tokenizer = self.processor.tokenizer
        features = self.processor(
            audio, sampling_rate=sr, return_tensors="pt"
        ).input_features.to(self.device)
        sot = tokenizer.convert_tokens_to_ids("<|startoftranscript|>")
        with torch.no_grad():
            logits = self.base(
                features,
                decoder_input_ids=torch.tensor([[sot]], device=self.device),
            ).logits[0, 0]
        lang_ids = {
            code: tokenizer.convert_tokens_to_ids(f"<|{code}|>")
            for code in ("sv", "en", "no", "da", "fi", "de", "ar")
        }
        probs = logits[list(lang_ids.values())].softmax(-1)
        best = int(probs.argmax())
        return list(lang_ids)[best], float(probs[best])

    # ---------- dialect ID ----------

    def train_head(self, manifests: list, out_dir: str | Path,
                   epochs: int = 8, lr: float = 1e-3) -> None:
        """Fit the logistic head on dialect-labeled manifests."""
        import soundfile as sf
        import torch

        from ..data.manifest import read_manifest

        feats, labels = [], []
        for m in manifests:
            for utt in read_manifest(m):
                if utt.dialect == "unknown":
                    continue
                audio, sr = sf.read(utt.audio_path, dtype="float32")
                feats.append(self._encode(audio, sr))
                labels.append(utt.dialect)
        if not feats:
            raise ValueError("no dialect-labeled utterances in manifests")

        self.labels = sorted(set(labels))
        x = torch.cat(feats)
        y = torch.tensor([self.labels.index(label) for label in labels], device=self.device)
        self.head = torch.nn.Linear(x.shape[1], len(self.labels)).to(self.device)
        opt = torch.optim.Adam(self.head.parameters(), lr=lr)
        for epoch in range(epochs):
            opt.zero_grad()
            loss = torch.nn.functional.cross_entropy(self.head(x), y)
            loss.backward()
            opt.step()
            log.info("router epoch %d loss %.4f", epoch, float(loss))
        self.head.eval()

        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        torch.save(
            {"labels": self.labels, "dim": x.shape[1], "head": self.head.state_dict()},
            out_dir / "router_head.pt",
        )
        (out_dir / "router_labels.json").write_text(json.dumps(self.labels))

    def route(self, audio, sr: int) -> list:
        """Ranked [(dialect, prob), ...] — best expert first."""
        import torch

        if self.head is None:
            return [("unknown", 1.0)]
        with torch.no_grad():
            probs = self.head(self._encode(audio, sr)).softmax(-1)[0]
        order = torch.argsort(probs, descending=True)
        return [(self.labels[int(i)], float(probs[int(i)])) for i in order]
