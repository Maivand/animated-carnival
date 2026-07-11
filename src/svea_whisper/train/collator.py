"""Padding collator for Whisper seq2seq fine-tuning."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass
class DataCollatorSpeechSeq2Seq:
    processor: Any

    def __call__(self, features: list) -> dict:
        import torch

        input_features = [{"input_features": f["input_features"]} for f in features]
        batch = self.processor.feature_extractor.pad(input_features, return_tensors="pt")

        label_features = [{"input_ids": f["labels"]} for f in features]
        labels_batch = self.processor.tokenizer.pad(label_features, return_tensors="pt")
        labels = labels_batch["input_ids"].masked_fill(
            labels_batch.attention_mask.ne(1), -100
        )
        # Trainer re-adds the BOS token during generation; strip it from labels.
        if (labels[:, 0] == self.processor.tokenizer.bos_token_id).all().cpu().item():
            labels = labels[:, 1:]
        batch["labels"] = labels
        return batch
