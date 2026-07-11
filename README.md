# SveaWhisper 🇸🇪

**Goal: the best Swedish speech-to-text model** — one that understands every Swedish
dialect (skånska, norrländska, gotländska, finlandssvenska, …) *and* modern slang
(förortssvenska / multietnolekt, youth speech), and that ships in three deployment
targets:

| Target  | Runtime                       | Format                     |
|---------|-------------------------------|----------------------------|
| GPU     | CTranslate2 / faster-whisper  | float16                    |
| CPU     | CTranslate2 / faster-whisper  | int8                       |
| Android | whisper.cpp                   | quantized GGUF (q5_0/q8_0) |

## Approach

We do **not** train from scratch. We start from
[KB-Whisper](https://huggingface.co/KBLab/kb-whisper-large) — the National Library of
Sweden's Whisper models, already trained on 50 000+ hours of Swedish — and fine-tune it
on a curated mix that adds what KB-Whisper lacks: **strong dialects and contemporary
slang**, sourced from open datasets plus a YouTube collection pipeline targeting
dialect- and slang-heavy channels.

```
                 ┌─────────────────────────────────────────────┐
                 │                DATA PIPELINE                │
  Common Voice ─►│                                             │
  FLEURS sv    ─►│  download ─► normalize ─► manifest (jsonl)  │
  NST / Rixvox ─►│                                             │──┐
                 └─────────────────────────────────────────────┘  │
                 ┌─────────────────────────────────────────────┐  │
  YouTube      ─►│ yt-dlp ─► VAD segment ─► pseudo-label with  │  │
  (dialect &     │           KB-Whisper ─► confidence filter   │──┤
   slang seeds)  └─────────────────────────────────────────────┘  │
                                                                  ▼
                 ┌─────────────────────────────────────────────┐
                 │  FINE-TUNE  KB-Whisper (tiny/small/large)   │
                 │  HF Seq2SeqTrainer · configs/train_*.yaml   │
                 └─────────────────────────────────────────────┘
                                                                  ▼
                 ┌─────────────────────────────────────────────┐
                 │  EVAL  WER/CER overall + per dialect region │
                 └─────────────────────────────────────────────┘
                                                                  ▼
                 ┌─────────────────────────────────────────────┐
                 │  EXPORT  CT2 fp16 (GPU) · CT2 int8 (CPU) ·  │
                 │          GGUF q5 (Android/whisper.cpp)      │
                 └─────────────────────────────────────────────┘
```

## Quickstart

```bash
pip install -e ".[train,data,export,dev]"

# 1. Build manifests from open datasets (Common Voice needs HF auth + accepted terms)
svea download --config configs/data.yaml --out data/manifests

# 2. Collect dialect/slang audio from YouTube (see docs/LEGAL.md first!)
svea youtube --seeds configs/youtube_seeds.yaml --out data/youtube
svea pseudolabel --audio-dir data/youtube --model KBLab/kb-whisper-large \
     --out data/manifests/youtube.jsonl

# 3. Fine-tune (GPU machine)
svea train --config configs/train_large.yaml

# 4. Evaluate (overall + per dialect)
svea eval --model runs/svea-large --manifest data/manifests/test.jsonl

# 5. Export all three deployment targets
svea export --model runs/svea-large --target gpu cpu android --out dist/
```

Smoke-test the whole pipeline on CPU with tiny synthetic data:

```bash
python scripts/smoke_test.py
```

## Repository layout

- `src/svea_whisper/data/` — dataset registry, downloaders, YouTube collection, VAD
  segmentation, pseudo-labeling, Swedish text normalization
- `src/svea_whisper/train/` — fine-tuning (HF Seq2SeqTrainer)
- `src/svea_whisper/eval/` — WER/CER evaluation with per-dialect breakdown
- `src/svea_whisper/export/` — CTranslate2, ONNX and GGUF export
- `configs/` — data mix and per-model-size training configs
- `android/` — on-device integration with whisper.cpp (docs + Kotlin example)
- `docs/` — data sources, training guide, deployment guide, legal notes on scraping

## Status / roadmap

- [x] Full pipeline code: data → train → eval → export
- [x] CPU smoke test of train/eval loop
- [ ] Run full data collection (needs HF auth for Common Voice, YouTube quota/time)
- [ ] Full fine-tune of kb-whisper-large on a GPU box (see docs/TRAINING.md for specs)
- [ ] Dialect-stratified test set with human-verified transcripts
- [ ] Publish exported models
