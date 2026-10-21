"""Measure JSON strings exported by CompactInspectionEvaluationTest."""
import argparse
import json
from pathlib import Path


def summarize(records, token_counter=None, tokenizer=None):
    samples = []
    for record in records:
        full, compact = record["full"], record["compact"]
        full_bytes, compact_bytes = len(full.encode("utf-8")), len(compact.encode("utf-8"))
        samples.append({
            "id": record["id"],
            "fullBytes": full_bytes,
            "compactBytes": compact_bytes,
            "byteReductionPercent": round(100 * (1 - compact_bytes / full_bytes), 2),
            "fullTokens": token_counter(full) if token_counter else None,
            "compactTokens": token_counter(compact) if token_counter else None,
        })
    return {"kind": "offline-synthetic-projection", "fixtureVersion": 1,
            "tokenizer": tokenizer, "agentUsageMeasured": False, "samples": samples}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--encoding", help="Optional tiktoken encoding, e.g. o200k_base")
    args = parser.parse_args()
    counter, tokenizer = None, None
    if args.encoding:
        import tiktoken  # Optional local evaluation tool, not a repository dependency.
        encoding = tiktoken.get_encoding(args.encoding)
        counter = lambda text: len(encoding.encode(text))
        tokenizer = f"tiktoken {tiktoken.__version__} / {args.encoding}"
    result = summarize(json.loads(args.export.read_text()), counter, tokenizer)
    args.output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
