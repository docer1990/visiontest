import argparse
import json
import sys
import time
import uuid
from pathlib import Path

from benchmarks.cli import run_cli
from benchmarks.manifest import load_manifest
from benchmarks.mcp import McpProtocolError, McpSession
from benchmarks.results import summarize, write_samples
from benchmarks.execution import run_process


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
        if args.lifecycle == "native-cold" and not _valid_native_lifecycle(manifest.get("nativeLifecycle")):
            raise ValueError("native-cold requires nativeLifecycle.stop and nativeLifecycle.start argv arrays")
        args.output.mkdir(parents=True, exist_ok=False)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 2

    samples = []
    samples_path = args.output / "samples.jsonl"
    write_samples(samples_path, samples)
    run_id = str(uuid.uuid4())
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
    environment["run_id"] = run_id
    (args.output / "environment.json").write_text(
        json.dumps(environment, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    failed = False
    sequence = 0
    for case in manifest["cases"]:
        attempts = [(True, index) for index in range(args.warmup)] + [(False, index) for index in range(args.samples)]
        warm_sessions = {}
        warm_session_errors = {}
        warm_timings = {}
        warm_timing_emitted = set()
        if args.mode == "mcp" and args.lifecycle in {"warm", "native-cold"}:
            states = {_tracing_for(args.tracing, index) for _, index in attempts}
            for state in states:
                session_trace = args.output / "traces" / f"{case['id']}-mcp-{state}.jsonl" if state == "on" else None
                if session_trace is not None:
                    session_trace.parent.mkdir(parents=True, exist_ok=True)
                try:
                    session = McpSession(
                        manifest["mcpPrefix"], case["timeoutMs"],
                        _mcp_environment(manifest, session_trace),
                    ).__enter__()
                    warm_sessions[state] = session
                    session.initialize()
                    _require_tools(session.list_tools(), case)
                    warm_timings[state] = dict(session.timings)
                except Exception as error:
                    warm_session_errors[state] = str(error)
        try:
            for attempt_index, (is_warmup, index) in enumerate(attempts):
                sequence += 1
                sample_id = f"{case['id']}-{'warmup' if is_warmup else 'sample'}-{index + 1}"
                tracing = _tracing_for(args.tracing, index)
                trace_path = args.output / "traces" / f"{sample_id}.jsonl" if tracing == "on" else None
                setup_duration = 0
                if tracing in warm_session_errors:
                    sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                     status="setup_failed", error=warm_session_errors[tracing])
                else:
                    if trace_path is not None:
                        trace_path.parent.mkdir(parents=True, exist_ok=True)
                    if args.lifecycle == "native-cold":
                        native_ok, native_ns, native_error = _native_restart(manifest["nativeLifecycle"], _environment_values(manifest), case["timeoutMs"])
                        setup_duration += native_ns
                    else:
                        native_ok, native_error = True, None
                    setup_ok, setup_ns, setup_error = _run_setup(case, _environment_values(manifest))
                    setup_duration += setup_ns
                    if not native_ok or not setup_ok:
                        sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                         status="setup_failed", error=native_error or setup_error,
                                         setup_duration_ns=setup_duration)
                    elif args.mode == "cli":
                        (args.output / "artifacts").mkdir(parents=True, exist_ok=True)
                        result = run_cli(manifest["cliPrefix"], case, env=_environment_values(manifest),
                                         output_dir=args.output / "artifacts", trace_path=trace_path)
                        status = _operation_status(result)
                        sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                         status=status, duration_ns=result["duration_ns"],
                                         exit_code=result["exit_code"], operation_outcome=result["operation_outcome"],
                                         assertion_outcome=result["assertion_outcome"], setup_duration_ns=setup_duration,
                                         error=_process_error(result))
                    else:
                        prepared = False
                        try:
                            (args.output / "artifacts").mkdir(parents=True, exist_ok=True)
                            if args.lifecycle == "host-cold":
                                if trace_path is not None:
                                    trace_path.parent.mkdir(parents=True, exist_ok=True)
                                with McpSession(manifest["mcpPrefix"], case["timeoutMs"],
                                                _mcp_environment(manifest, trace_path)) as session:
                                    session.initialize()
                                    tools = session.list_tools()
                                    _require_tools(tools, case)
                                    prepared = True
                                    timings = dict(session.timings)
                                    result = session.run_case(case, output_dir=args.output / "artifacts")
                            else:
                                session = warm_sessions[tracing]
                                timings = warm_timings[tracing] if tracing not in warm_timing_emitted else {}
                                warm_timing_emitted.add(tracing)
                                (args.output / "artifacts").mkdir(parents=True, exist_ok=True)
                                result = session.run_case(case, output_dir=args.output / "artifacts")
                            sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                             status=_operation_status(result), duration_ns=result["duration_ns"],
                                             exit_code=result["exit_code"], operation_outcome=result["operation_outcome"],
                                             assertion_outcome=result["assertion_outcome"], setup_duration_ns=setup_duration,
                                             startup_duration_ns=timings.get("launch_ns"),
                                             initialization_duration_ns=timings.get("initialize_ns"),
                                             discovery_duration_ns=timings.get("discovery_ns"),
                                             error=result.get("failure"))
                        except Exception as error:
                            sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                             status="failed" if prepared else "setup_failed",
                                             duration_ns=None, operation_outcome="failed" if prepared else "not_run",
                                             assertion_outcome="failed" if prepared else "not_run",
                                             setup_duration_ns=setup_duration,
                                             error=str(error))
                samples.append(sample)
                failed |= sample["status"] != "passed" and not is_warmup
                write_samples(samples_path, samples)
                (args.output / "summary.json").write_text(
                    json.dumps(summarize(samples), indent=2, sort_keys=True) + "\n", encoding="utf-8"
                )
        finally:
            for session in warm_sessions.values():
                session.__exit__(None, None, None)
    return 1 if failed else 0


def _valid_native_lifecycle(value):
    return isinstance(value, dict) and all(
        isinstance(value.get(key), list) and value[key] and all(isinstance(arg, str) and arg for arg in value[key])
        for key in ("stop", "start")
    )


def _environment_values(manifest):
    return dict(manifest.get("processEnvironment", {}))


def _mcp_environment(manifest, trace_path):
    environment = _environment_values(manifest)
    if trace_path is not None:
        environment["VISIONTEST_TRACE_PERFORMANCE"] = str(trace_path)
    return environment


def _run_setup(case, env):
    started = time.perf_counter_ns()
    for argv in case["setup"]:
        result = run_process(argv, case["timeoutMs"], env)
        if result["timed_out"] or result["exit_code"] != 0:
            return False, time.perf_counter_ns() - started, _process_error(result)
    return True, time.perf_counter_ns() - started, None


def _native_restart(config, env, timeout_ms):
    started = time.perf_counter_ns()
    for key in ("stop", "start"):
        result = run_process(config[key], timeout_ms, env)
        if result["timed_out"] or result["exit_code"] != 0:
            return False, time.perf_counter_ns() - started, _process_error(result)
    return True, time.perf_counter_ns() - started, None


def _require_tools(tools, case):
    names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
    required = {case["mcpTool"]}
    if case["assertion"]["kind"] in {"action_then_element", "wait_then_element"}:
        required.add(case["assertion"]["lookupTool"])
    missing = required - names
    if missing:
        raise McpProtocolError(f"MCP server does not provide configured tools: {', '.join(sorted(missing))}")


def _sample(run_id, sample_id, case, args, tracing, warmup, sequence, *, status, duration_ns=None,
            exit_code=None, operation_outcome="not_run", assertion_outcome="not_run",
            setup_duration_ns=0, error=None, startup_duration_ns=None,
            initialization_duration_ns=None, discovery_duration_ns=None):
    sample = {
        "schema_version": 1, "run_id": run_id, "sample_id": sample_id, "case_id": case["id"],
        "mode": args.mode, "lifecycle": args.lifecycle, "tracing": tracing, "warmup": warmup,
        "status": status, "duration_ns": duration_ns, "setup_duration_ns": setup_duration_ns,
        "exit_code": exit_code, "operation_outcome": operation_outcome,
        "assertion_outcome": assertion_outcome, "trace_complete": None, "measurement_gaps": [],
        "sample_sequence": sequence,
    }
    for key, value in (("startup_duration_ns", startup_duration_ns),
                       ("initialization_duration_ns", initialization_duration_ns),
                       ("discovery_duration_ns", discovery_duration_ns)):
        if value is not None:
            sample[key] = value
    if error:
        sample["failure"] = error
    return sample


def _operation_status(result):
    if result.get("timed_out"):
        return "timeout"
    return "passed" if result.get("operation_outcome") == "success" and result.get("assertion_outcome") == "passed" else "failed"


def _process_error(result):
    if result.get("timed_out"):
        return "operation timed out; device outcome is uncertain"
    if result.get("exit_code") not in (None, 0):
        return f"process exited with code {result['exit_code']}: {result.get('stderr', '')[-1000:]}"
    return None


def _tracing_for(tracing, index):
    if tracing == "paired":
        return "off" if index % 2 == 0 else "on"
    return tracing


if __name__ == "__main__":
    raise SystemExit(main())
