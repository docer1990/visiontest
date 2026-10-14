import argparse
import json
import sys
from pathlib import Path

from benchmarks.manifest import load_manifest
from benchmarks.results import summarize, write_samples


def _parser():
    parser = argparse.ArgumentParser(prog="python3 -m benchmarks.run")
    subparsers = parser.add_subparsers(dest="command", required=True)
    commands = subparsers.add_parser("commands")
    commands.add_argument("--manifest", required=True, type=Path)
    commands.add_argument("--output", required=True, type=Path)
    commands.add_argument("--mode", choices=("cli", "mcp"), required=True)
    commands.add_argument("--samples", type=int, default=30)
    commands.add_argument("--warmup", type=int, default=5)
    commands.add_argument("--tracing", choices=("on", "off", "paired"), default="off")
    commands.add_argument("--lifecycle", choices=("warm", "host-cold", "native-cold"), default="warm")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return int(error.code)
    if args.samples <= 0 or args.warmup < 0:
        print("samples must be positive and warmup nonnegative", file=sys.stderr)
        return 2
    try:
        manifest = load_manifest(args.manifest)
        args.output.mkdir(parents=True, exist_ok=False)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 2

    samples = []
    write_samples(args.output / "samples.jsonl", samples)
    environment = {
        "schema_version": 1,
        "platform": manifest["platform"],
        "target": manifest["target"],
        "fixture_revision": manifest["fixtureRevision"],
        "environment": manifest["environment"],
        "mode": args.mode,
        "lifecycle": args.lifecycle,
        "tracing": args.tracing,
        "requested_samples": args.samples,
        "requested_warmups": args.warmup,
    }
    (args.output / "environment.json").write_text(
        json.dumps(environment, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (args.output / "summary.json").write_text(
        json.dumps(summarize(samples), indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print("benchmark adapters are not available yet", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
