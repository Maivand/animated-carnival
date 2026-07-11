#!/usr/bin/env bash
# End-to-end SveaWhisper pipeline on a GPU box. Produces the two product
# models — svea-edge (on-device) and svea-large (server) — each trained in
# two stages: general Swedish, then slang/dialect specialization.
# Idempotent-ish: yt-dlp keeps a download archive, manifests are overwritten.
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFESTS=data/manifests
YT_RAW=data/youtube/raw
YT_SEG=data/youtube/segments

echo "=== 1/7 open datasets ==="
svea download --out "$MANIFESTS" --split train
svea download --out "$MANIFESTS" --sources common_voice fleurs --split test

echo "=== 2/7 youtube collection (slang/dialect focus) ==="
svea youtube --seeds configs/youtube_seeds.yaml --out "$YT_RAW"

echo "=== 3/7 VAD segmentation ==="
for d in "$YT_RAW"/*/; do
  svea segment --in-dir "$d" --out-dir "$YT_SEG/$(basename "$d")"
  cp "$d/_seed_meta.json" "$YT_SEG/$(basename "$d")/" 2>/dev/null || true
done

echo "=== 4/7 pseudo-labeling ==="
svea pseudolabel --audio-dir "$YT_SEG" --out "$MANIFESTS/youtube.jsonl"
svea stats "$MANIFESTS/youtube.jsonl"
# Slang/dialect subset for the stage-2 specialization passes
svea filter --manifest "$MANIFESTS/youtube.jsonl" \
  --dialects fororts sydsvenska norrlandska ostsvenska dalmal gotlandska gotamal unknown \
  --min-confidence 0.7 \
  --out "$MANIFESTS/youtube_slang_dialect.jsonl"

echo "=== 5/7 sanity run on tiny (fails fast on data bugs) ==="
svea train --config configs/train_tiny.yaml

echo "=== 6/7 product models: edge + large, two stages each ==="
svea train --config configs/train_edge.yaml
svea train --config configs/train_edge_stage2_slang.yaml
svea train --config configs/train_large.yaml
svea train --config configs/train_large_stage2_slang.yaml

echo "=== 7/7 eval (stage1 vs stage2!) + export ==="
for run in svea-edge svea-edge-slang svea-large svea-large-slang; do
  svea eval --model "runs/$run/final" \
            --manifest "$MANIFESTS/common_voice.test.jsonl" \
            --out-json "runs/$run/eval_cv.json"
  svea eval --model "runs/$run/final" \
            --manifest "$MANIFESTS/youtube_slang_dialect.jsonl" \
            --out-json "runs/$run/eval_slang.json"
done

# Ship stage-2 unless it regressed on Common Voice (check eval_cv.json diffs
# before publishing; the pipeline exports both and the report shows both).
svea export --model runs/svea-large-slang/final --target gpu --out dist/large
svea export --model runs/svea-edge-slang/final --target cpu android --out dist/edge

echo "done — edge artifacts in dist/edge, server artifacts in dist/large"
