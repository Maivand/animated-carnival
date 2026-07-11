"""Offline test fixtures.

Builds a tiny random Whisper model + processor entirely locally (byte-level
tokenizer, default mel feature extractor), so the train/eval pipeline can be
smoke-tested on machines with no Hugging Face access. Quality is meaningless;
plumbing is real.
"""

from __future__ import annotations

import json
from pathlib import Path


def build_tiny_model(out_dir: str | Path) -> Path:
    from transformers import (
        GenerationConfig,
        WhisperConfig,
        WhisperFeatureExtractor,
        WhisperForConditionalGeneration,
        WhisperProcessor,
        WhisperTokenizer,
    )
    try:  # transformers >= 5
        from transformers.convert_slow_tokenizer import bytes_to_unicode
    except ImportError:  # transformers 4.x
        from transformers.models.gpt2.tokenization_gpt2 import bytes_to_unicode

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    from transformers.models.whisper.tokenization_whisper import LANGUAGES

    # Byte-level vocab (256 tokens) + Whisper's control tokens, no merges ->
    # pure byte fallback tokenizer. The layout must mirror the real Whisper
    # vocab: language tokens contiguous after <|startoftranscript|>, because
    # the tokenizer computes language ids as sot_id + 1 + LANGUAGES index.
    specials = (
        ["<|endoftext|>", "<|startoftranscript|>"]
        + [f"<|{code}|>" for code in LANGUAGES]
        + ["<|translate|>", "<|transcribe|>", "<|startoflm|>",
           "<|startofprev|>", "<|nospeech|>", "<|notimestamps|>"]
    )
    vocab = {ch: i for i, ch in enumerate(bytes_to_unicode().values())}
    for token in specials:
        vocab[token] = len(vocab)

    common = dict(
        unk_token="<|endoftext|>", bos_token="<|endoftext|>",
        eos_token="<|endoftext|>", pad_token="<|endoftext|>",
        additional_special_tokens=specials,
        language="sv", task="transcribe", predict_timestamps=False,
    )
    import inspect

    if "vocab" in inspect.signature(WhisperTokenizer.__init__).parameters:
        # transformers >= 5: in-memory vocab/merges, fast backend
        tokenizer = WhisperTokenizer(vocab=vocab, merges=[], **common)
    else:
        # transformers 4.x: slow tokenizer reads files
        (out_dir / "vocab.json").write_text(json.dumps(vocab), encoding="utf-8")
        (out_dir / "merges.txt").write_text("#version: 0.2\n", encoding="utf-8")
        tokenizer = WhisperTokenizer(
            vocab_file=str(out_dir / "vocab.json"),
            merges_file=str(out_dir / "merges.txt"),
            **common,
        )
    tid = tokenizer.convert_tokens_to_ids

    config = WhisperConfig(
        vocab_size=len(tokenizer),
        d_model=64, encoder_layers=2, decoder_layers=2,
        encoder_attention_heads=2, decoder_attention_heads=2,
        encoder_ffn_dim=128, decoder_ffn_dim=128,
        num_mel_bins=80, max_source_positions=1500, max_target_positions=448,
        pad_token_id=tid("<|endoftext|>"),
        bos_token_id=tid("<|endoftext|>"),
        eos_token_id=tid("<|endoftext|>"),
        decoder_start_token_id=tid("<|startoftranscript|>"),
        suppress_tokens=[], begin_suppress_tokens=[],
    )
    model = WhisperForConditionalGeneration(config)
    model.generation_config = GenerationConfig(
        decoder_start_token_id=tid("<|startoftranscript|>"),
        pad_token_id=tid("<|endoftext|>"),
        eos_token_id=tid("<|endoftext|>"),
        bos_token_id=tid("<|endoftext|>"),
        max_length=64,
        lang_to_id={"<|sv|>": tid("<|sv|>"), "<|en|>": tid("<|en|>")},
        task_to_id={"transcribe": tid("<|transcribe|>"),
                    "translate": tid("<|translate|>")},
        no_timestamps_token_id=tid("<|notimestamps|>"),
        suppress_tokens=[], begin_suppress_tokens=[],
    )

    processor = WhisperProcessor(WhisperFeatureExtractor(feature_size=80), tokenizer)
    model.save_pretrained(out_dir)
    processor.save_pretrained(out_dir)
    return out_dir


def hf_hub_reachable(timeout: float = 5.0) -> bool:
    import urllib.request

    try:
        urllib.request.urlopen("https://huggingface.co", timeout=timeout)
        return True
    except Exception:
        return False
