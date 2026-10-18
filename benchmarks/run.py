import argparse
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path

from benchmarks.cli import run_cli
from benchmarks.manifest import load_manifest
from benchmarks.mcp import McpProtocolError, McpSession
from benchmarks.results import write_samples
from benchmarks.report import build_report
from benchmarks.traces import join_trace
from benchmarks.execution import run_process
from benchmarks.fixtures import resolve_argv, validate_fixture_identity, validate_target, wait_fixture_ready


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
    run_id = str(uuid.uuid4())
    args.output = Path(str(args.output).replace('{run_id}', run_id))
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
    environment = {
        "schema_version": 1,
        "manifest_sha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        "selected_case_ids": [case['id'] for case in manifest['cases']],
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
        attempts = _attempts(args.warmup, args.samples, args.tracing)
        trace_jobs = {}
        warm_sessions = {}
        warm_session_errors = {}
        warm_timings = {}
        warm_timing_emitted = set()
        if args.mode == "mcp" and args.lifecycle in {"warm", "native-cold"}:
            states = {state for _, _, state, _ in attempts}
            for state in sorted(states):
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
            for is_warmup, index, tracing, pair_order in attempts:
                sequence += 1
                sample_id = f"{case['id']}-{'warmup' if is_warmup else 'sample'}-{index + 1}"
                if args.tracing == 'paired':
                    sample_id += '-' + tracing
                artifact_dir = args.output / "artifacts" / str(sequence)
                result = None
                process_duration = None
                calls = []
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
                    setup_ok, setup_ns, setup_error = _run_setup(case, manifest, _environment_values(manifest))
                    setup_duration += setup_ns
                    if not native_ok or not setup_ok:
                        sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                         status="setup_failed", error=native_error or setup_error,
                                         setup_duration_ns=setup_duration)
                    elif args.mode == "cli":
                        artifact_dir.mkdir(parents=True, exist_ok=True)
                        result = run_cli(manifest["cliPrefix"], case, env=_environment_values(manifest),
                                         output_dir=artifact_dir, trace_path=trace_path)
                        process_duration = result['duration_ns']
                        calls = [{'sample_id': sample_id, 'operation': case['cliArgs'][0], 'measured': True}]
                        status = _operation_status(result)
                        sample = _sample(run_id, sample_id, case, args, tracing, is_warmup, sequence,
                                         status=status, duration_ns=result["duration_ns"],
                                         exit_code=result["exit_code"], operation_outcome=result["operation_outcome"],
                                         assertion_outcome=result["assertion_outcome"], setup_duration_ns=setup_duration,
                                         error=_process_error(result))
                    else:
                        prepared = False
                        try:
                            artifact_dir.mkdir(parents=True, exist_ok=True)
                            if args.lifecycle == "host-cold":
                                if trace_path is not None:
                                    trace_path.parent.mkdir(parents=True, exist_ok=True)
                                process_started = time.perf_counter_ns()
                                with McpSession(manifest["mcpPrefix"], case["timeoutMs"],
                                                _mcp_environment(manifest, trace_path)) as session:
                                    session.initialize()
                                    tools = session.list_tools()
                                    _require_tools(tools, case)
                                    prepared = True
                                    timings = dict(session.timings)
                                    result = session.run_case(case, output_dir=artifact_dir)
                                    calls = _dispatches(session, 0, sample_id)
                                process_duration = time.perf_counter_ns() - process_started
                            else:
                                session = warm_sessions[tracing]
                                timings = warm_timings[tracing] if tracing not in warm_timing_emitted else {}
                                warm_timing_emitted.add(tracing)
                                artifact_dir.mkdir(parents=True, exist_ok=True)
                                prepared = True
                                first_call = len(session.calls)
                                result = session.run_case(case, output_dir=artifact_dir)
                                calls = _dispatches(session, first_call, sample_id)
                                trace_path = (args.output / 'traces' / f"{case['id']}-mcp-{tracing}.jsonl"
                                              if tracing == 'on' else None)
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
                if result and result.get("screenshot_path"):
                    sample["screenshot_path"] = str(Path(result["screenshot_path"]).relative_to(args.output))
                sample['fixture_revision'] = manifest['fixtureRevision']
                sample['process_duration_ns'] = process_duration
                sample['operation_duration_ns'] = result['duration_ns'] if result and args.mode == 'mcp' else None
                if args.tracing == 'paired':
                    sample['pair_id'] = f"{case['id']}-{'warmup' if is_warmup else 'sample'}-{index + 1}"
                    sample['pair_order'] = pair_order
                if trace_path is not None and calls:
                    sample['trace_file'] = str(trace_path.relative_to(args.output))
                    sample['trace_dispatches'] = calls
                    trace_jobs.setdefault(trace_path, []).extend(calls)
                samples.append(sample)
                failed |= sample["status"] != "passed" and not is_warmup
                write_samples(samples_path, samples)
                _write_summary(args.output, samples)
        finally:
            for session in warm_sessions.values():
                session.__exit__(None, None, None)
            for path, calls in trace_jobs.items():
                joined = join_trace(path, calls)
                for sample in samples:
                    if sample['sample_id'] in joined:
                        sample.update(joined[sample['sample_id']])
            write_samples(samples_path, samples)
            _write_summary(args.output, samples)
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
    environment["VISIONTEST_TRACE_PERFORMANCE"] = str(trace_path) if trace_path is not None else ''
    return environment


def _run_setup(case, manifest, env):
    started = time.perf_counter_ns()
    try:
        validate_fixture_identity(manifest["platform"], manifest["fixtureIdentity"])
        target = manifest["target"]
        if manifest["platform"] == "android":
            devices = run_process(["adb", "devices"], case["timeoutMs"])
            available = [line.split()[0] for line in devices["stdout"].splitlines()[1:]
                         if len(line.split()) >= 2 and line.split()[1] == "device"]
        else:
            devices = run_process(["xcrun", "simctl", "list", "devices", "booted", "-j"], case["timeoutMs"])
            data = json.loads(devices["stdout"])
            available = [device["udid"] for devices_on_runtime in data.get("devices", {}).values()
                         for device in devices_on_runtime if device.get("state") == "Booted"]
        if devices["timed_out"] or devices["exit_code"] != 0:
            return False, time.perf_counter_ns() - started, _process_error(devices)
        validate_target(manifest["platform"], target, available)
        artifact = Path(manifest["fixtureArtifact"]).resolve()
        if not artifact.exists():
            raise ValueError(f"benchmark fixture artifact is missing: {artifact}")
        tokens = {"target": target, "fixtureArtifact": str(artifact),
                  "fixtureIdentity": manifest["fixtureIdentity"],
                  "scenario": case["fixtureScenario"], "language": case["fixtureLanguage"]}
    except (ValueError, KeyError, json.JSONDecodeError) as error:
        return False, time.perf_counter_ns() - started, str(error)
    installed = False
    for index, configured_argv in enumerate(case["setup"]):
        try:
            argv = resolve_argv(configured_argv, tokens)
        except ValueError as error:
            return False, time.perf_counter_ns() - started, str(error)
        if Path(argv[0]).name in {"sh", "bash", "zsh", "cmd", "powershell"}:
            return False, time.perf_counter_ns() - started, "shell setup commands are not allowed"
        result = run_process(argv, case["timeoutMs"], env)
        allowed = case.get("setupBenignExitCodes", [[] for _ in case["setup"]])[index]
        if result["timed_out"] or result["exit_code"] not in [0, *allowed]:
            return False, time.perf_counter_ns() - started, _process_error(result)
        if "install" in argv and not installed:
            if manifest["platform"] == "android":
                identity_check = ["adb", "-s", target, "shell", "pm", "path", manifest["fixtureIdentity"]]
            else:
                identity_check = ["xcrun", "simctl", "get_app_container", target,
                                  manifest["fixtureIdentity"], "app"]
            verified = run_process(identity_check, case["timeoutMs"])
            if verified["timed_out"] or verified["exit_code"] != 0:
                return False, time.perf_counter_ns() - started, "installed fixture identity could not be verified"
            installed = True
    if not installed:
        return False, time.perf_counter_ns() - started, "setup must install the pinned benchmark fixture"
    ready, error = wait_fixture_ready(case, manifest, env)
    return ready, time.perf_counter_ns() - started, error


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


def _attempts(warmups, samples, tracing):
    attempts = []
    for warmup, count in ((True, warmups), (False, samples)):
        for index in range(count):
            states = (('off', 'on') if index % 2 == 0 else ('on', 'off')) if tracing == 'paired' else (tracing,)
            attempts.extend((warmup, index, state, order + 1) for order, state in enumerate(states))
    return attempts


def _dispatches(session, first, sample_id):
    return [{'sample_id': sample_id, 'operation': operation, 'measured': index == first,
             'dispatch_sequence': index + 1}
            for index, operation in enumerate(session.calls) if index >= first]


def _write_summary(output, samples):
    (output / 'summary.json').write_text(
        json.dumps(build_report(samples), indent=2, sort_keys=True) + '\n', encoding='utf-8')


if __name__ == "__main__":
    raise SystemExit(main())
