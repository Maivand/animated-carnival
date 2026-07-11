#!/usr/bin/env bash
# Phase 2 (after run_full_pipeline.sh): dialect experts + MoE evaluation.
# Trains the router, fine-tunes per-dialect experts from svea-large-slang,
# and runs the real-WER A/B of MoE vs the plain generalist that decides
# whether the ensemble ships.
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFESTS=data/manifests
GENERALIST=runs/svea-large-slang/final
EXPERT_DIALECTS=(fororts sydsvenska ostsvenska dalmal)
EXPERT_STEPS=4000

echo "=== 1/3 dialect router ==="
svea train-router \
  --manifests "$MANIFESTS/youtube.jsonl" "$MANIFESTS/youtube_subs.jsonl" \
  --out runs/router

echo "=== 2/3 dialect experts (from the slang-specialized generalist) ==="
for dialect in "${EXPERT_DIALECTS[@]}"; do
  svea filter --manifest "$MANIFESTS/youtube.jsonl" --dialects "$dialect" \
    --min-confidence 0.7 --out "$MANIFESTS/expert_$dialect.jsonl"
  # Skip dialects with <10h of data — an expert would just overfit.
  hours=$(svea stats "$MANIFESTS/expert_$dialect.jsonl" | python3 -c "import sys,json;print(json.load(sys.stdin)['hours'])")
  if python3 -c "import sys; sys.exit(0 if float('$hours') >= 10 else 1)"; then
    cat > "configs/expert_$dialect.yaml" <<EOF
base_model: $GENERALIST
train_manifests:
  - $MANIFESTS/expert_$dialect.jsonl
  - $MANIFESTS/common_voice.train.jsonl
eval_manifest: $MANIFESTS/common_voice.test.jsonl
output_dir: runs/expert-$dialect
learning_rate: 2.0e-6
warmup_steps: 100
max_steps: $EXPERT_STEPS
per_device_batch_size: 8
gradient_accumulation: 4
eval_steps: 500
save_steps: 500
fp16: true
gradient_checkpointing: true
min_label_confidence: 0.7
EOF
    svea train --config "configs/expert_$dialect.yaml"
  else
    echo "skipping expert $dialect: only ${hours}h of data (<10h)"
  fi
done

echo "=== 3/3 MoE vs generalist A/B ==="
EXPERT_ARGS=()
for dialect in "${EXPERT_DIALECTS[@]}"; do
  [ -d "runs/expert-$dialect/final" ] && EXPERT_ARGS+=("$dialect=runs/expert-$dialect/final")
done
svea eval-moe \
  --manifest "$MANIFESTS/youtube_slang_dialect.jsonl" \
  --generalist "$GENERALIST" \
  --router runs/router \
  --expert "${EXPERT_ARGS[@]}" \
  --out-json runs/moe_ab_report.json

echo "done — decision report in runs/moe_ab_report.json"
