# RunPod plan: main model training on one pod

## GPU requirement

Fine-tuning `kb-whisper-large` (1.54B params, AdamW, bf16 mixed precision,
gradient checkpointing) needs ≈ 32–40 GB VRAM at batch 8–16:
weights 3 GB + fp32 master weights 6 GB + Adam moments 12 GB + grads 3 GB
+ activations/overhead. So:

- **48 GB cards (A40, RTX A6000, L40S) are the minimum comfortable fit.**
- 24 GB (RTX 4090) only works with an 8-bit optimizer and tiny batches — not
  worth the fragility for a multi-day run.
- 80 GB (A100) buys headroom + bigger batches, at ~3× the price of an A40.

## Pricing (RunPod, checked July 2026)

| GPU | VRAM | Community $/h | Notes |
|-----|------|---------------|-------|
| **A40** | 48 GB | **~$0.44** | cheapest 48 GB; Ampere, ~85% of A6000 speed |
| RTX A6000 | 48 GB | ~$0.49 | slightly faster than A40 |
| L40S | 48 GB | ~$0.99 | Ada, ~1.7× A40 speed but 2.2× price |
| A100 80 GB | 80 GB | ~$1.39 | ~1.8× A40 speed, 3× price |
| RTX 4090 | 24 GB | ~$0.34 | too little VRAM for large fine-tune |

Storage: container/volume disk $0.10/GB/mo while running — **$0.20/GB/mo
while STOPPED** (rate doubles!); network volume $0.07/GB/mo. Billing is
per-second; spot pods are 50–80% cheaper but can be killed with 5 s notice.

## Decision: A40 48 GB, Community Cloud, on-demand

Cheapest card that fits the job. Estimated budget for the full pipeline on
one pod:

| Stage | est. GPU-hours | est. cost |
|-------|----------------|-----------|
| Data download + VAD segmentation | ~6 | $3 |
| Pseudo-labeling ~500 h YouTube audio | ~50 | $22 |
| Fine-tune tiny + small | ~12 | $5 |
| Fine-tune large (40k steps, eff. batch 32) | ~48 | $21 |
| Eval + exports | ~4 | $2 |
| 150 GB volume for ~5 days | — | ~$3 |
| **Total** | **~120 h** | **~$55–75** |

Spot would cut this to ~$25–35; with `save_steps` checkpointing the training
stages resume cleanly, but pseudo-labeling restarts lose partial work unless
sharded — the launcher supports `--spot` once we add manifest sharding.
On-demand first; optimize later if we rerun often.

(An A100 80 GB run is the fallback if A40 availability is poor: same total
work ≈ $95–140, finishes ~1.8× sooner.)

## Not wasting money — how the pod is prevented from idling

All enforced by `scripts/runpod/` (no manual babysitting):

1. **Self-termination**: the pod's entrypoint runs the pipeline, uploads
   artifacts, then `runpodctl remove pod $RUNPOD_POD_ID` — *remove*, not
   *stop*, because a stopped pod keeps billing storage at the doubled rate.
   The trap runs even if the pipeline fails.
2. **Idle watchdog**: background loop terminates the pod if GPU utilization
   stays under 5% for 30 min (hung job, crashed dataloader, forgotten shell).
3. **Hard runtime cap**: `MAX_HOURS` (default 60) terminates the pod
   unconditionally — an upper bound on any single run's cost (~$26 on A40).
4. **Artifacts leave the pod before it dies**: checkpoints/evals/exports are
   uploaded (Hugging Face repo via `HF_TOKEN`, or any S3-compatible bucket)
   so terminating loses nothing.
5. **Per-second billing** means aggressive termination costs nothing extra.

## Launching

```bash
export RUNPOD_API_KEY=...   # and HF_TOKEN for artifact upload
python scripts/runpod/launch_pod.py \
    --gpu "NVIDIA A40" --disk 150 --max-hours 60 \
    --repo https://github.com/Maivand/animated-carnival \
    --branch claude/swedish-whisper-model-d2yvx2
python scripts/runpod/launch_pod.py --status   # check on it
python scripts/runpod/launch_pod.py --kill     # emergency stop (terminates)
```

Sources for pricing: [runpod.io/pricing](https://www.runpod.io/pricing),
[docs.runpod.io/pods/pricing](https://docs.runpod.io/pods/pricing),
[northflank.com RunPod pricing breakdown](https://northflank.com/blog/runpod-gpu-pricing),
[costbench.com](https://costbench.com/software/ai-gpu-cloud/runpod/).
