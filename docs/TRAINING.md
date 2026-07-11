# Training guide

## Hardware

| Config | Base model | GPU | Wall time (est.) |
|--------|-----------|-----|------------------|
| `train_large.yaml` | kb-whisper-large (1.5B) | 1× A100 80GB | ~3–4 days for 40k steps |
| `train_small.yaml` | kb-whisper-small (244M) | 1× RTX 4090 / A10G | ~1 day |
| `train_tiny.yaml` | kb-whisper-tiny (39M) | any 12GB+ GPU | hours |

Rented options: RunPod/Lambda A100-80GB ≈ $1.5–2/h → full large run ≈ $150–200.

## Recipe notes

- **Start from KB-Whisper, not openai/whisper** — it is already 47%+ better on
  Swedish than the OpenAI checkpoints; we fine-tune to add dialects/slang.
- **Low LR** (6.25e-6 for large): the model is already in a good Swedish
  basin; high LR causes catastrophic forgetting of the long tail.
- **Pseudo-label filtering**: `min_label_confidence` in the config re-filters
  YouTube data at load time. If dialect WER improves but overall WER regresses,
  raise it (0.6 → 0.75) before touching anything else.
- **Eval during training** runs on the Common Voice test manifest; run the full
  per-dialect eval (`svea eval`) on checkpoints you're considering shipping.
- **Order of experiments**: tiny → small → large. Tiny turns around in hours
  and exposes data bugs cheaply; the mix that wins on small almost always wins
  on large.

## Full pipeline on a fresh GPU box

```bash
git clone <this repo> && cd <repo>
pip install -e ".[train,data,export]"
huggingface-cli login          # for common_voice terms
bash scripts/run_full_pipeline.sh
```
