"""Fine-tune KB-Whisper on the SveaWhisper data mix.

Config-driven (configs/train_*.yaml). Uses HF Seq2SeqTrainer with the standard
Whisper fine-tuning recipe: log-mel input features, tokenized Swedish targets,
WER on a held-out manifest during training.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import yaml

from ..data.manifest import read_manifest
from ..data.normalize_sv import normalize_eval

log = logging.getLogger(__name__)


@dataclass
class TrainConfig:
    base_model: str
    train_manifests: list
    eval_manifest: str
    output_dir: str
    language: str = "sv"
    learning_rate: float = 1e-5
    warmup_steps: int = 500
    max_steps: int = 20000
    per_device_batch_size: int = 16
    gradient_accumulation: int = 2
    eval_steps: int = 1000
    save_steps: int = 1000
    fp16: bool = True
    gradient_checkpointing: bool = True
    freeze_encoder: bool = False
    min_label_confidence: float = 0.0  # drop pseudo-labels below this
    seed: int = 42

    @classmethod
    def from_yaml(cls, path: str | Path) -> "TrainConfig":
        with Path(path).open(encoding="utf-8") as f:
            raw = yaml.safe_load(f)
        # PyYAML parses bare scientific notation ("2e-6") as a string.
        raw["learning_rate"] = float(raw.get("learning_rate", cls.learning_rate))
        return cls(**raw)


def _load_hf_dataset(manifests: list, min_confidence: float):
    """Materialize manifests as a HF audio dataset."""
    from datasets import Audio, Dataset

    rows = {"audio": [], "text": []}
    for m in manifests:
        for utt in read_manifest(m):
            if utt.pseudo_labeled and utt.label_confidence < min_confidence:
                continue
            rows["audio"].append(utt.audio_path)
            rows["text"].append(utt.text)
    if not rows["audio"]:
        raise ValueError(f"no usable utterances in {manifests}")
    return Dataset.from_dict(rows).cast_column("audio", Audio(sampling_rate=16_000))


def train(config_path: str | Path) -> Path:
    import jiwer
    import torch
    from transformers import (
        Seq2SeqTrainer,
        Seq2SeqTrainingArguments,
        WhisperForConditionalGeneration,
        WhisperProcessor,
    )

    from .collator import DataCollatorSpeechSeq2Seq

    cfg = TrainConfig.from_yaml(config_path)
    processor = WhisperProcessor.from_pretrained(
        cfg.base_model, language=cfg.language, task="transcribe"
    )
    model = WhisperForConditionalGeneration.from_pretrained(cfg.base_model)
    model.generation_config.language = cfg.language
    model.generation_config.task = "transcribe"
    model.config.forced_decoder_ids = None
    if cfg.freeze_encoder:
        model.freeze_encoder()

    def prepare(batch):
        audio = batch["audio"]
        batch["input_features"] = processor(
            audio["array"], sampling_rate=audio["sampling_rate"], return_tensors="np"
        ).input_features[0]
        batch["labels"] = processor.tokenizer(batch["text"]).input_ids
        return batch

    train_ds = _load_hf_dataset(cfg.train_manifests, cfg.min_label_confidence).map(
        prepare, remove_columns=["audio", "text"]
    )
    eval_ds = _load_hf_dataset([cfg.eval_manifest], 0.0).map(
        prepare, remove_columns=["audio", "text"]
    )

    def compute_metrics(pred):
        label_ids = pred.label_ids
        label_ids[label_ids == -100] = processor.tokenizer.pad_token_id
        hyps = processor.batch_decode(pred.predictions, skip_special_tokens=True)
        refs = processor.batch_decode(label_ids, skip_special_tokens=True)
        # Guard against empty refs after normalization (crashes jiwer)
        pairs = [(normalize_eval(r), normalize_eval(h)) for r, h in zip(refs, hyps)]
        pairs = [(r, h) for r, h in pairs if r]
        if not pairs:
            return {"wer": 1.0}
        return {"wer": jiwer.wer([r for r, _ in pairs], [h for _, h in pairs])}

    args = Seq2SeqTrainingArguments(
        output_dir=cfg.output_dir,
        per_device_train_batch_size=cfg.per_device_batch_size,
        gradient_accumulation_steps=cfg.gradient_accumulation,
        learning_rate=cfg.learning_rate,
        warmup_steps=cfg.warmup_steps,
        max_steps=cfg.max_steps,
        fp16=cfg.fp16 and torch.cuda.is_available(),
        gradient_checkpointing=cfg.gradient_checkpointing,
        eval_strategy="steps",
        eval_steps=cfg.eval_steps,
        save_steps=cfg.save_steps,
        predict_with_generate=True,
        generation_max_length=225,
        load_best_model_at_end=True,
        metric_for_best_model="wer",
        greater_is_better=False,
        logging_steps=50,
        report_to=["tensorboard"],
        seed=cfg.seed,
        remove_unused_columns=False,
    )
    trainer = Seq2SeqTrainer(
        model=model,
        args=args,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        data_collator=DataCollatorSpeechSeq2Seq(
            processor=processor,
            decoder_start_token_id=model.config.decoder_start_token_id,
        ),
        compute_metrics=compute_metrics,
        processing_class=processor,
    )
    trainer.train()
    out = Path(cfg.output_dir) / "final"
    trainer.save_model(out)
    processor.save_pretrained(out)
    log.info("saved fine-tuned model to %s", out)
    return out
