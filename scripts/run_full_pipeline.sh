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
YT_SUBS=data/youtube/sub_segments

echo "=== 1/8 open datasets ==="
svea download --out "$MANIFESTS" --split train
svea download --out "$MANIFESTS" --sources common_voice fleurs --split test

echo "=== 2/8 youtube collection (slang/dialect focus) ==="
svea youtube --seeds configs/youtube_seeds.yaml --out "$YT_RAW"

echo "=== 3/8 harvest manual subtitles (HUMAN labels — best slang data) ==="
svea harvest-subs --audio-dir "$YT_RAW" --out "$MANIFESTS/youtube_subs.jsonl" \
  --out-audio-dir "$YT_SUBS"
# Videos with subs are done — move their raw audio aside so VAD +
# pseudo-labeling only process the unlabeled remainder.
python3 - <<'EOF'
import json, pathlib
ids = json.loads(pathlib.Path("data/manifests/youtube_subs.harvested_ids.json").read_text())
for wav in pathlib.Path("data/youtube/raw").rglob("*.wav"):
    if wav.stem in ids:
        done = wav.parent / "subbed"
        done.mkdir(exist_ok=True)
        wav.rename(done / wav.name)
print(f"quarantined {len(ids)} subtitled videos from the pseudo-label path")
EOF

echo "=== 4/8 VAD segmentation + pseudo-labeling (unsubtitled remainder) ==="
for d in "$YT_RAW"/*/; do
  svea segment --in-dir "$d" --out-dir "$YT_SEG/$(basename "$d")"
  cp "$d/_seed_meta.json" "$YT_SEG/$(basename "$d")/" 2>/dev/null || true
done
svea pseudolabel --audio-dir "$YT_SEG" --out "$MANIFESTS/youtube.jsonl"
svea stats "$MANIFESTS/youtube.jsonl"

echo "=== 5/8 build weighted training mix + slang subset ==="
svea mix --out "$MANIFESTS/train_mix.jsonl" --manifests \
  "$MANIFESTS/rixvox.train.jsonl" "$MANIFESTS/nst.train.jsonl" \
  "$MANIFESTS/common_voice.train.jsonl" "$MANIFESTS/fleurs.train.jsonl" \
  "$MANIFESTS/youtube.jsonl" "$MANIFESTS/youtube_subs.jsonl"
# Slang/dialect subset for stage-2 (human subs included at full confidence)
svea filter --manifest "$MANIFESTS/youtube.jsonl" \
  --dialects fororts sydsvenska norrlandska ostsvenska dalmal gotlandska gotamal unknown \
  --min-confidence 0.7 \
  --out "$MANIFESTS/youtube_slang_pseudo.jsonl"
cat "$MANIFESTS/youtube_slang_pseudo.jsonl" "$MANIFESTS/youtube_subs.jsonl" \
  > "$MANIFESTS/youtube_slang_dialect.jsonl"

echo "=== 6/8 sanity run on tiny (fails fast on data bugs) ==="
svea train --config configs/train_tiny.yaml

echo "=== 7/8 product models: edge + large, two stages each ==="
svea train --config configs/train_edge.yaml
svea train --config configs/train_edge_stage2_slang.yaml
svea train --config configs/train_large.yaml
svea train --config configs/train_large_stage2_slang.yaml

echo "=== 8/8 eval (stage1 vs stage2!) + export ==="
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

echo "done — edge artifacts in dist/edge, server artifacts in dist/large."
echo "next (optional): bash scripts/run_experts_pipeline.sh for MoE experts"
