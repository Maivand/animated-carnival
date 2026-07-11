#!/usr/bin/env bash
# Runs ON the RunPod pod. Full pipeline with three layers of cost protection:
#   1. hard runtime cap (MAX_HOURS, default 60)
#   2. idle watchdog (GPU <5% util for 30 min => terminate)
#   3. self-termination on exit — REMOVE, not stop: stopped pods keep billing
#      storage at a doubled rate.
# Artifacts are uploaded to $SVEA_UPLOAD_REPO (HF hub) before termination.
set -uo pipefail
cd "$(dirname "$0")/../.."

MAX_HOURS="${MAX_HOURS:-60}"
IDLE_LIMIT_MIN="${IDLE_LIMIT_MIN:-30}"

terminate() {
  echo "[pod] terminating self ($1)"
  runpodctl remove pod "$RUNPOD_POD_ID"
}
trap 'terminate "exit trap"' EXIT

# --- layer 1: hard cap ---
( sleep "$((MAX_HOURS * 3600))"; echo "[pod] MAX_HOURS reached"; terminate "hard cap" ) &

# --- layer 2: idle watchdog ---
(
  idle=0
  while sleep 60; do
    util=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits | head -1)
    if [ "${util:-0}" -lt 5 ]; then idle=$((idle + 1)); else idle=0; fi
    if [ "$idle" -ge "$IDLE_LIMIT_MIN" ]; then
      echo "[pod] GPU idle ${IDLE_LIMIT_MIN}min"
      terminate "idle watchdog"
    fi
  done
) &

# --- work ---
pip install -e ".[train,data,export]" 2>&1 | tail -1
apt-get update -qq && apt-get install -y -qq ffmpeg 2>&1 | tail -1

bash scripts/run_full_pipeline.sh 2>&1 | tee /workspace/pipeline.log
STATUS=$?

# --- upload artifacts before dying ---
if [ -n "${SVEA_UPLOAD_REPO:-}" ] && [ -n "${HF_TOKEN:-}" ]; then
  pip install -q huggingface_hub
  python - <<'EOF'
import os
from huggingface_hub import HfApi

api = HfApi(token=os.environ["HF_TOKEN"])
repo = os.environ["SVEA_UPLOAD_REPO"]
api.create_repo(repo, private=True, exist_ok=True)
for path in ("runs", "dist", "/workspace/pipeline.log"):
    if os.path.exists(path):
        if os.path.isdir(path):
            api.upload_folder(folder_path=path, repo_id=repo, path_in_repo=os.path.basename(path))
        else:
            api.upload_file(path_or_fileobj=path, repo_id=repo, path_in_repo=os.path.basename(path))
print("upload complete")
EOF
else
  echo "[pod] WARNING: no SVEA_UPLOAD_REPO/HF_TOKEN — artifacts stay on the volume only"
fi

echo "[pod] pipeline exit status: $STATUS"
# EXIT trap terminates the pod here.
