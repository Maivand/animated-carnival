#!/usr/bin/env bash
# End-to-end SveaWhisper pipeline on a GPU box. Idempotent-ish: yt-dlp keeps a
# download archive, manifests are overwritten per stage.
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFESTS=data/manifests
YT_RAW=data/youtube/raw
YT_SEG=data/youtube/segments

echo "=== 1/6 open datasets ==="
svea download --out "$MANIFESTS" --split train
svea download --out "$MANIFESTS" --sources common_voice fleurs --split test

echo "=== 2/6 youtube collection ==="
svea youtube --seeds configs/youtube_seeds.yaml --out "$YT_RAW"

echo "=== 3/6 VAD segmentation ==="
for d in "$YT_RAW"/*/; do
  svea segment --in-dir "$d" --out-dir "$YT_SEG/$(basename "$d")"
  cp "$d/_seed_meta.json" "$YT_SEG/$(basename "$d")/" 2>/dev/null || true
done

echo "=== 4/6 pseudo-labeling ==="
svea pseudolabel --audio-dir "$YT_SEG" --out "$MANIFESTS/youtube.jsonl"
svea stats "$MANIFESTS/youtube.jsonl"

echo "=== 5/6 training (tiny -> small -> large) ==="
svea train --config configs/train_tiny.yaml
svea train --config configs/train_small.yaml
svea train --config configs/train_large.yaml

echo "=== 6/6 eval + export ==="
for size in tiny small large; do
  svea eval --model "runs/svea-$size/final" \
            --manifest "$MANIFESTS/common_voice.test.jsonl" \
            --out-json "runs/svea-$size/eval.json"
done
svea export --model runs/svea-large/final --target gpu --out dist/large
svea export --model runs/svea-small/final --target cpu --out dist/small
svea export --model runs/svea-tiny/final  --target android --out dist/tiny

echo "done — deployment artifacts in dist/"
