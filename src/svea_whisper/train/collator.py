"""Padding collator for Whisper seq2seq fine-tuning."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass
class DataCollatorSpeechSeq2Seq:
    processor: Any
    decoder_start_token_id: int = -1  # pass model.config.decoder_start_token_id

    def __call__(self, features: list) -> dict:
        input_features = [{"input_features": f["input_features"]} for f in features]
        batch = self.processor.feature_extractor.pad(input_features, return_tensors="pt")

        label_features = [{"input_ids": f["labels"]} for f in features]
        labels_batch = self.processor.tokenizer.pad(label_features, return_tensors="pt")
        labels = labels_batch["input_ids"].masked_fill(
            labels_batch.attention_mask.ne(1), -100
        )
        # The model prepends decoder_start (SOT) itself via shift_tokens_right;
        # strip it from labels when the tokenizer already added it.
        if (labels[:, 0] == self.decoder_start_token_id).all().cpu().item():
            labels = labels[:, 1:]
        batch["labels"] = labels
        return batch
