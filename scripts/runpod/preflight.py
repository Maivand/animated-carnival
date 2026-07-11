#!/usr/bin/env python3
"""Launch-day preflight: verify everything needed to start the RunPod run.

Run this the moment credentials/network land; if all checks pass it prints
the exact launch command. Exit code 0 = ready.
"""

from __future__ import annotations

import os
import shutil
import urllib.request

CHECKS = []


def check(name):
    def deco(fn):
        CHECKS.append((name, fn))
        return fn
    return deco


def _reachable(url: str) -> bool:
    try:
        req = urllib.request.Request(url, method="HEAD")
        urllib.request.urlopen(req, timeout=10)
        return True
    except urllib.error.HTTPError:
        return True  # got an HTTP response — host is reachable
    except Exception:
        return False


@check("RUNPOD_API_KEY set")
def _key():
    return bool(os.environ.get("RUNPOD_API_KEY"))


@check("HF_TOKEN set (artifact upload + Common Voice terms)")
def _hf_token():
    return bool(os.environ.get("HF_TOKEN"))


@check("RunPod API reachable")
def _runpod():
    return _reachable("https://rest.runpod.io/v1/pods")


@check("Hugging Face reachable")
def _hf():
    return _reachable("https://huggingface.co")


@check("YouTube reachable (only needed if scraping from this box)")
def _yt():
    return _reachable("https://www.youtube.com")


@check("git available")
def _git():
    return shutil.which("git") is not None


def main() -> int:
    required_failures = 0
    for i, (name, fn) in enumerate(CHECKS):
        ok = fn()
        optional = "only needed if" in name
        mark = "PASS" if ok else ("WARN" if optional else "FAIL")
        print(f"[{mark}] {name}")
        if not ok and not optional:
            required_failures += 1

    if required_failures:
        print(f"\n{required_failures} required check(s) failing — see docs/RUNPOD.md "
              "for environment setup (env vars + network allowlist).")
        return 1

    print(
        "\nREADY. Launch with:\n"
        "  python scripts/runpod/launch_pod.py \\\n"
        "      --gpu 'NVIDIA A40' --disk 150 --max-hours 60 \\\n"
        "      --upload-repo <your-hf-username>/svea-whisper-artifacts\n"
        "Then: python scripts/runpod/launch_pod.py --status"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
