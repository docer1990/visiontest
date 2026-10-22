"""Paired full-flow recording measurements. Importing this module does not access devices."""

import argparse
import hashlib
import json
import pathlib
import platform
import shlex
import statistics
import subprocess
import time


def phase_durations(start_ns, ready_ns, executed_ns, finished_ns):
    if not start_ns <= ready_ns <= executed_ns <= finished_ns:
        raise ValueError("Phase timestamps must be ordered")
    return {
        "startupMs": (ready_ns - start_ns) / 1_000_000,
        "executionMs": (executed_ns - ready_ns) / 1_000_000,
        "finalizationMs": (finished_ns - executed_ns) / 1_000_000,
    }


def summarize_samples(samples: list[dict]) -> dict:
    rounds = {}
    failed = 0
    for sample in samples:
        key = (sample["round"], sample["mode"])
        if sample["mode"] not in ("plain", "record") or key in rounds:
            raise ValueError(f"Invalid or duplicate round/mode: {key}")
        rounds[key] = sample
        failed += not sample["success"] or (sample["mode"] == "record" and sample["videoValid"] is not True)
    deltas = [
        record["elapsedMs"] - rounds[(round_number, "plain")]["elapsedMs"]
        for (round_number, mode), record in rounds.items()
        if mode == "record" and (round_number, "plain") in rounds
        and record["success"] and record["videoValid"] is True
        and rounds[(round_number, "plain")]["success"]
    ]
    return {"totalSamples": len(samples), "failedSamples": failed,
            "pairedMedianDeltaMs": statistics.median(deltas) if deltas else None}


FIXTURE = "com.example.visiontest.benchmark"


def fixture_selector(platform_name, identifier, target=False):
    if platform_name == "android":
        return ["--target-content-description" if target else "--content-description", identifier]
    return ["--target-resource-id" if target else "--resource-id", identifier, "--bundle-id", FIXTURE]


def fixture_steps(platform_name):
    steps = [["input_text", "flow-benchmark@example.com", *fixture_selector(platform_name, "email", target=True)]]
    if platform_name == "android":
        steps.append(["press_back"])
    return steps + [
        ["tap_on_element", *fixture_selector(platform_name, "continue")],
        ["wait_for_element", *fixture_selector(platform_name, "done")],
    ]


def traced_flow_command(flow, trace_path, record=False):
    command = flow[:3] + ["--trace-performance", str(trace_path)] + flow[3:]
    return command + (["--record"] if record else [])


def run_probe(platform_name, target, jar, output, rounds=7, warmups=2):
    if platform_name not in ("android", "ios") or rounds < 1 or warmups < 0:
        raise ValueError("Expected android or ios, positive rounds and nonnegative warmups")
    jar, output = pathlib.Path(jar).resolve(), pathlib.Path(output).resolve()
    if not jar.is_file():
        raise FileNotFoundError(jar)
    prefix = ["java", "-jar", str(jar)]

    def command(argv, timeout=180):
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)

    def checked(argv):
        result = command(argv)
        if result.returncode:
            raise RuntimeError(f"exit {result.returncode}: {shlex.join(argv)}: {result.stderr.strip()}")
        return result.stdout

    if platform_name == "android":
        devices = checked(["adb", "devices"]).splitlines()[1:]
        active = [line.split()[0] for line in devices if line.endswith("\tdevice")]
    else:
        inventory = json.loads(checked(["xcrun", "simctl", "list", "devices", "booted", "--json"]))
        active = [device["udid"] for group in inventory["devices"].values()
                  for device in group if device["state"] == "Booted"]
    if active != [target]:
        raise RuntimeError(f"Expected one isolated active target {target}, found {active}")
    if platform_name == "android":
        checked(["adb", "-s", target, "shell", "pm", "path", FIXTURE])
    else:
        checked(["xcrun", "simctl", "get_app_container", target, FIXTURE, "app"])

    output.mkdir(parents=True, exist_ok=True)
    metadata = {
        "sourceCommit": checked(["git", "rev-parse", "HEAD"]).strip(),
        "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "host": platform.platform(), "platform": platform_name, "target": target,
        "fixture": FIXTURE, "rounds": rounds, "warmups": warmups,
        "order": "plain,record on even rounds; record,plain on odd rounds",
        "timing": "perf_counter_ns from flow subprocess spawn to exit, including startup and finalization",
        "phaseTiming": "Unavailable without external lifecycle instrumentation; no report timing fields are inferred",
        "commands": [], "toolVersions": {},
    }
    for key, argv in (("java", ["java", "-version"]), ("visiontest", prefix + ["--version"]),
                      ("native", ["adb", "version"] if platform_name == "android" else ["xcodebuild", "-version"])):
        result = command(argv)
        metadata["toolVersions"][key] = (result.stdout + result.stderr).strip()
    (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    def reset():
        if platform_name == "android":
            checked(["adb", "-s", target, "shell", "am", "force-stop", FIXTURE])
            checked(["adb", "-s", target, "shell", "am", "start", "-n", f"{FIXTURE}/.MainActivity",
                     "--es", "scenario", "navigation", "--es", "language", "en"])
        else:
            command(["xcrun", "simctl", "terminate", target, FIXTURE])
            checked(["xcrun", "simctl", "launch", target, FIXTURE, "navigation", "en"])
        selector = fixture_selector(platform_name, "fixture_ready")
        ready = json.loads(checked(prefix + ["wait_for_element", "-p", platform_name] + selector + ["--json"]))
        if ready.get("found") is not True or ready.get("satisfied") is not True:
            raise RuntimeError(f"Fixture not ready: {ready}")

    steps = fixture_steps(platform_name)
    flow = prefix + ["flow", "-p", platform_name, "--json", "--timeout", "20000"]
    if platform_name == "ios":
        flow += ["--bundle-id", FIXTURE]
    for step in steps:
        flow += ["--step", shlex.join(step)]
    flow += ["--observe", "interactive-elements"]
    metadata["commands"] = {"flow": flow, "reset": "fixture-only restart and fixture_ready wait"}
    (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    samples = []
    with (output / "samples.jsonl").open("w") as stream:
        for round_number in range(rounds + warmups):
            modes = ("plain", "record") if round_number % 2 == 0 else ("record", "plain")
            for mode in modes:
                sample = {"round": round_number, "mode": mode, "warmup": round_number < warmups,
                          "success": False, "elapsedMs": None, "videoValid": None if mode == "plain" else False,
                          "phaseMs": None}
                try:
                    reset()
                    trace = output / f"{round_number}-{mode}.trace.jsonl"
                    sample["tracePath"] = str(trace)
                    argv = traced_flow_command(flow, trace, record=mode == "record")
                    start = time.perf_counter_ns()
                    sample["processStartedNs"] = start
                    result = command(argv, timeout=90)
                    end = time.perf_counter_ns()
                    sample["processFinishedNs"] = end
                    sample["elapsedMs"] = (end - start) / 1_000_000
                    report = json.loads(result.stdout)
                    sample["exitCode"] = result.returncode
                    sample["error"] = report.get("error")
                    sample["recording"] = report.get("recording")
                    sample["success"] = result.returncode == 0 and report.get("success") is True and all(
                        step["status"] == "succeeded" for step in report["steps"])
                    sample["success"] &= any(element.get("resourceId", "").endswith("done")
                        or element.get("contentDescription") == "done"
                        for observation in report["observations"] if observation["status"] == "succeeded"
                        for element in observation["result"].get("elements", []))
                    if mode == "record":
                        sample["videoPath"] = report.get("recording", {}).get("path")
                        sample["videoValid"] = False
                        sample["videoValidation"] = "pending decoded first/last frame transition inspection"
                        sample["success"] &= report.get("recording", {}).get("status") == "succeeded"
                except (OSError, ValueError, KeyError, subprocess.TimeoutExpired, RuntimeError) as error:
                    sample["error"] = str(error)
                samples.append(sample)
                stream.write(json.dumps(sample) + "\n")
                stream.flush()
    summary = summarize_samples([sample for sample in samples if not sample["warmup"]])
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("android", "ios"), required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--jar", default="app/build/libs/visiontest.jar")
    parser.add_argument("--output", required=True)
    parser.add_argument("--rounds", type=int, default=7)
    parser.add_argument("--warmups", type=int, default=2)
    args = parser.parse_args()
    print(json.dumps(run_probe(args.platform, args.target, args.jar, args.output, args.rounds, args.warmups)))


if __name__ == "__main__":
    main()
