#!/usr/bin/env python3
"""Launch (and manage) the SveaWhisper training pod on RunPod.

Creates a single on-demand GPU pod that clones the repo, runs the full
pipeline, uploads artifacts, and REMOVES ITSELF — see docs/RUNPOD.md for the
cost model. Requires RUNPOD_API_KEY (and HF_TOKEN for artifact upload).

Usage:
  launch_pod.py --gpu "NVIDIA A40" --disk 150 --max-hours 60 \
      --repo <git url> --branch <branch>
  launch_pod.py --status          # list our pods with uptime and cost/h
  launch_pod.py --kill            # terminate all svea-whisper pods NOW
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request

API = "https://rest.runpod.io/v1"
POD_NAME = "svea-whisper-train"


def _request(method: str, path: str, body: dict | None = None) -> dict:
    key = os.environ.get("RUNPOD_API_KEY")
    if not key:
        sys.exit("RUNPOD_API_KEY is not set")
    req = urllib.request.Request(
        f"{API}{path}",
        method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read() or "{}")


def docker_command(repo: str, branch: str, max_hours: int) -> str:
    """Entrypoint: clone -> run pipeline via pod_entrypoint.sh.

    Self-termination, the idle watchdog and the hard cap all live in
    pod_entrypoint.sh so they are version-controlled with the repo.
    """
    inner = (
        f"git clone --depth 1 -b {branch} {repo} /workspace/svea && "
        f"cd /workspace/svea && MAX_HOURS={max_hours} bash scripts/runpod/pod_entrypoint.sh"
    )
    # Belt and braces: even if the clone itself fails, remove the pod.
    return f'bash -c "({inner}); runpodctl remove pod $RUNPOD_POD_ID"'


def launch(args: argparse.Namespace) -> None:
    body = {
        "name": POD_NAME,
        "imageName": "runpod/pytorch:2.4.0-py3.11-cuda12.4.1-devel-ubuntu22.04",
        "gpuTypeIds": [args.gpu],
        "gpuCount": 1,
        "cloudType": "COMMUNITY" if not args.secure else "SECURE",
        "interruptible": args.spot,
        "containerDiskInGb": 20,
        "volumeInGb": args.disk,
        "volumeMountPath": "/workspace",
        "dockerEntrypoint": [],
        "dockerStartCmd": ["bash", "-c", docker_command(args.repo, args.branch, args.max_hours)],
        "env": {
            "HF_TOKEN": os.environ.get("HF_TOKEN", ""),
            "HF_HOME": "/workspace/hf-cache",
            "SVEA_UPLOAD_REPO": args.upload_repo,
        },
    }
    pod = _request("POST", "/pods", body)
    print(json.dumps(pod, indent=2))
    print(f"\nlaunched pod {pod.get('id')} — it will terminate itself when done "
          f"(hard cap {args.max_hours}h)")


def status() -> None:
    pods = _request("GET", "/pods")
    ours = [p for p in pods if p.get("name") == POD_NAME]
    if not ours:
        print("no svea-whisper pods running — nothing is billing")
        return
    print(json.dumps(ours, indent=2))


def kill() -> None:
    pods = _request("GET", "/pods")
    for p in pods:
        if p.get("name") == POD_NAME:
            _request("DELETE", f"/pods/{p['id']}")
            print(f"terminated {p['id']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gpu", default="NVIDIA A40")
    parser.add_argument("--disk", type=int, default=150, help="volume GB")
    parser.add_argument("--max-hours", type=int, default=60)
    parser.add_argument("--repo", default="https://github.com/Maivand/animated-carnival")
    parser.add_argument("--branch", default="claude/swedish-whisper-model-d2yvx2")
    parser.add_argument("--upload-repo", default="", help="HF repo for artifacts, e.g. user/svea-whisper")
    parser.add_argument("--secure", action="store_true", help="Secure Cloud instead of Community")
    parser.add_argument("--spot", action="store_true", help="interruptible pod (cheaper, can be killed)")
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--kill", action="store_true")
    args = parser.parse_args()

    if args.status:
        status()
    elif args.kill:
        kill()
    else:
        launch(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
