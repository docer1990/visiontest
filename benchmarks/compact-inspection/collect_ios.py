"""Collect full/compact iOS fixture pairs on an isolated ready simulator."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import time
import urllib.request


BUNDLE = "dev.visiontest.benchmark"
ROOT = Path(__file__).resolve().parents[2]
SCOPE = " --bundle-id " + BUNDLE
CASES = [
    ("form", "navigation", [
        "input_text agent@example.test --target-resource-id email" + SCOPE,
        "wait_for_element --resource-id email" + SCOPE,
    ]),
    ("navigation", "navigation", [
        "tap_on_element --resource-id continue" + SCOPE,
        "wait_for_element --resource-id done" + SCOPE,
    ]),
    ("ambiguous", "ambiguous", [
        "tap_on_element --resource-id continue_secondary" + SCOPE,
        "wait_for_element --resource-id continue_secondary" + SCOPE,
    ]),
    ("missing", "missing", [
        "wait_for_element --resource-id never-present --timeout 500" + SCOPE,
    ]),
]


def rpc(method, params):
    request = urllib.request.Request(
        "http://localhost:9009/jsonrpc",
        data=json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def exception_record(error):
    record = {"error": str(error), "errorType": type(error).__name__}
    if isinstance(error, subprocess.TimeoutExpired):
        # TimeoutExpired may contain bytes even when subprocess uses text=True.
        record.update({
            "exitCode": None,
            "rawStdout": output_text(error.stdout),
            "stderr": output_text(error.stderr),
        })
    return record


def output_text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value or ""


def reset(udid, scenario):
    process = subprocess.run(
        ["xcrun", "simctl", "launch", "--terminate-running-process", udid, BUNDLE, scenario, "en"],
        capture_output=True, text=True, timeout=30,
    )
    if process.returncode:
        return {"ok": False, "exitCode": process.returncode, "error": process.stderr}
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        result = rpc("ui.findElement", {"resourceId": "fixture_ready", "bundleId": BUNDLE}).get("result", {})
        if result.get("found") is True:
            return {"ok": True}
        if result.get("error"):
            return {"ok": False, "error": result["error"]}
        time.sleep(0.1)
    return {"ok": False, "error": "Fixture readiness timeout"}


def record_command(argv, encoding):
    start = time.monotonic()
    record = {"exitCode": None, "rawStdout": "", "stdout": None, "stderr": ""}
    try:
        process = subprocess.run(argv, capture_output=True, text=True, timeout=60)
        record.update({
            "exitCode": process.returncode,
            "rawStdout": process.stdout,
            "stderr": process.stderr,
        })
    except Exception as error:
        record.update(exception_record(error))
    record["durationMs"] = round((time.monotonic() - start) * 1000, 3)
    raw = record["rawStdout"]
    record["bytes"] = len(raw.encode("utf-8"))
    record["estimatedTokens"] = len(encoding.encode(raw))
    if "error" not in record:
        try:
            record["stdout"] = json.loads(raw)
        except ValueError as error:
            record.update(exception_record(error))
    return record


def commands_for(steps, compact):
    base = ["java", "-jar", str(ROOT / "app/build/libs/visiontest.jar")]
    scope = ["-p", "ios", "--bundle-id", BUNDLE, "--json"]
    options = ["--compact"] if compact else []
    return [
        ("inspection", base + ["get_interactive_elements"] + scope + options),
        ("flow", base + ["flow"] + scope + [
            "--observe", "interactive-elements", "--on-failure", "interactive-elements",
        ] + [arg for step in steps for arg in ("--step", step)] + options),
    ]


def collect(report, output, udid, encoding):
    for name, scenario, steps in CASES:
        for compact in (False, True):
            sample = {"case": name, "mode": "compact" if compact else "full"}
            report["samples"].append(sample)
            try:
                sample["setup"] = reset(udid, scenario)
            except Exception as error:
                sample["setup"] = {"ok": False, **exception_record(error)}
            if sample["setup"]["ok"]:
                for kind, argv in commands_for(steps, compact):
                    sample[kind] = record_command(argv, encoding)
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            statuses = {
                kind: sample[kind].get("error", sample[kind].get("exitCode"))
                for kind in ("inspection", "flow") if kind in sample
            }
            print(name, sample["mode"], statuses, flush=True)


def main():
    import tiktoken  # Local evaluation dependency only.

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--udid", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(exist_ok=False, parents=True)
    encoding = tiktoken.get_encoding("o200k_base")
    report = {
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "platform": "ios", "simulator": args.udid, "runtime": args.runtime,
        "host": platform.platform(),
        "tokenizer": {
            "name": "tiktoken", "version": tiktoken.__version__, "encoding": encoding.name,
            "accounting": "estimated stdout text tokens; no agent/provider usage",
        },
        "samples": [],
        "methodology": (
            "One sample per case/mode; setup outside timing; warm native server, "
            "fresh JVM per invocation; no latency inference; full then compact order; "
            "all attempted samples retained."
        ),
    }
    artifacts = {
        "jar": "app/build/libs/visiontest.jar",
        "fixture": (
            "benchmarks/fixtures/ios/.derivedData/Build/Products/"
            "Debug-iphonesimulator/BenchmarkFixture.app/BenchmarkFixture"
        ),
    }
    for name, path in artifacts.items():
        report[name + "Sha256"] = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
    output = args.output / "results.json"
    collect(report, output, args.udid, encoding)
    print(output)


if __name__ == "__main__":
    main()
