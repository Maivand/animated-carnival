"""`svea` command-line interface — one entrypoint per pipeline stage."""

from __future__ import annotations

import argparse
import json
import logging


def main(argv: list | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s: %(message)s")
    parser = argparse.ArgumentParser(prog="svea", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("download", help="build manifests from open HF datasets")
    p.add_argument("--out", required=True)
    p.add_argument("--sources", nargs="*", default=None)
    p.add_argument("--split", default="train")
    p.add_argument("--max-hours", type=float, default=None)

    p = sub.add_parser("youtube", help="collect dialect/slang audio from YouTube seeds")
    p.add_argument("--seeds", required=True)
    p.add_argument("--out", required=True)

    p = sub.add_parser("segment", help="VAD-segment long audio into utterances")
    p.add_argument("--in-dir", required=True)
    p.add_argument("--out-dir", required=True)

    p = sub.add_parser("pseudolabel", help="transcribe segments with KB-Whisper")
    p.add_argument("--audio-dir", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--model", default="KBLab/kb-whisper-large")

    p = sub.add_parser("harvest-subs", help="cut human-labeled utterances from manual subs")
    p.add_argument("--audio-dir", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--out-audio-dir", required=True)

    p = sub.add_parser("mix", help="build the weighted training mix manifest")
    p.add_argument("--manifests", nargs="+", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--seed", type=int, default=42)

    p = sub.add_parser("train-router", help="train the dialect router head")
    p.add_argument("--manifests", nargs="+", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--base", default="KBLab/kb-whisper-tiny")

    p = sub.add_parser("eval-moe", help="A/B: MoE ensemble vs generalist, real WER")
    p.add_argument("--manifest", required=True)
    p.add_argument("--generalist", required=True)
    p.add_argument("--router", required=True)
    p.add_argument("--expert", nargs="+", default=[], help="dialect=model_path pairs")
    p.add_argument("--out-json", default=None)

    p = sub.add_parser("stats", help="print manifest statistics")
    p.add_argument("manifest")

    p = sub.add_parser("filter", help="subset a manifest (dialects/confidence/sources)")
    p.add_argument("--manifest", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--dialects", nargs="*", default=None)
    p.add_argument("--sources", nargs="*", default=None)
    p.add_argument("--min-confidence", type=float, default=0.0)

    p = sub.add_parser("train", help="fine-tune from a YAML config")
    p.add_argument("--config", required=True)

    p = sub.add_parser("eval", help="WER/CER overall + per dialect")
    p.add_argument("--model", required=True)
    p.add_argument("--manifest", required=True)
    p.add_argument("--out-json", default=None)

    p = sub.add_parser("export", help="export gpu/cpu/android deployment formats")
    p.add_argument("--model", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--target", nargs="+", default=["gpu", "cpu", "android"])

    args = parser.parse_args(argv)

    if args.command == "download":
        from .data.download import build_all
        build_all(args.out, args.sources, args.split, args.max_hours)
    elif args.command == "youtube":
        from .data.youtube import collect
        collect(args.seeds, args.out)
    elif args.command == "segment":
        from .data.preprocess import segment_directory
        segment_directory(args.in_dir, args.out_dir)
    elif args.command == "pseudolabel":
        from .data.pseudo_label import transcribe_directory
        transcribe_directory(args.audio_dir, args.out, model_id=args.model)
    elif args.command == "harvest-subs":
        from .data.subs import harvest_directory
        print(json.dumps(harvest_directory(args.audio_dir, args.out, args.out_audio_dir)))
    elif args.command == "mix":
        from .data.mix import mix_manifests
        print(json.dumps(mix_manifests(args.manifests, args.out, seed=args.seed), indent=2))
    elif args.command == "train-router":
        from .moe.router import DialectRouter
        router = DialectRouter(base_model=args.base)
        router.train_head(args.manifests, args.out)
        router.base.save_pretrained(args.out)
        router.processor.save_pretrained(args.out)
        print(f"router saved -> {args.out}")
    elif args.command == "eval-moe":
        from .eval.evaluate_moe import evaluate_moe
        experts = dict(pair.split("=", 1) for pair in args.expert)
        report = evaluate_moe(args.manifest, args.generalist, experts,
                              args.router, out_json=args.out_json)
        print(json.dumps(report, indent=2, ensure_ascii=False))
    elif args.command == "stats":
        from .data.manifest import manifest_stats
        print(json.dumps(manifest_stats(args.manifest), indent=2, ensure_ascii=False))
    elif args.command == "filter":
        from .data.manifest import filter_manifest
        n = filter_manifest(args.manifest, args.out, args.dialects,
                            args.min_confidence, args.sources)
        print(f"wrote {n} utterances -> {args.out}")
    elif args.command == "train":
        from .train.finetune import train
        train(args.config)
    elif args.command == "eval":
        from .eval.evaluate import evaluate_manifest
        report = evaluate_manifest(args.model, args.manifest, out_json=args.out_json)
        print(json.dumps(report, indent=2, ensure_ascii=False))
    elif args.command == "export":
        from .export.export import export_all
        print(json.dumps(export_all(args.model, args.out, args.target), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
