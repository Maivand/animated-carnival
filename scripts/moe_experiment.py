#!/usr/bin/env python3
"""Grid experiment over the MoE simulation: sweep router accuracy and
confidence-noise, print a report, save JSON.

Usage: python scripts/moe_experiment.py [--fast]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "src"))

from svea_whisper.moe.simulate import SimConfig, run  # noqa: E402

STRATEGIES = [
    "generalist_only", "route_hard", "dual_arbitrate",
    "dual_reroute", "oracle_router", "oracle_arbitration",
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fast", action="store_true", help="smaller sample size")
    parser.add_argument("--out", default=None, help="write JSON results here")
    args = parser.parse_args()
    n = 4000 if args.fast else 30000

    grid = []
    for router_acc in (0.70, 0.85, 0.95):
        for conf_noise in (0.02, 0.05, 0.10):
            cfg = SimConfig(router_acc=router_acc, conf_noise=conf_noise, n_utterances=n)
            grid.append({
                "router_acc": router_acc,
                "conf_noise": conf_noise,
                "results": run(cfg),
            })

    header = "| router acc | conf noise | " + " | ".join(STRATEGIES) + " |"
    sep = "|" + "---|" * (2 + len(STRATEGIES))
    print("\nMean WER by strategy (decodes/utterance in parens)\n")
    print(header)
    print(sep)
    for row in grid:
        cells = []
        for s in STRATEGIES:
            r = row["results"][s]
            cells.append(f"{r['wer']:.4f} ({r['decodes']:.1f})")
        print(f"| {row['router_acc']:.2f} | {row['conf_noise']:.2f} | " + " | ".join(cells) + " |")

    baseline = grid[4]["results"]  # router 0.85 / noise 0.05 — the central case
    gen = baseline["generalist_only"]["wer"]
    dual = baseline["dual_reroute"]["wer"]
    print(f"\nCentral case (router 85%, medium confidence noise): "
          f"generalist {gen:.4f} -> dual+reroute {dual:.4f} "
          f"({(1 - dual / gen) * 100:.1f}% relative WER reduction, "
          f"{baseline['dual_reroute']['decodes']:.1f} decodes/utt)")

    if args.out:
        Path(args.out).write_text(json.dumps(grid, indent=2))
        print(f"saved -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
