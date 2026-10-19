import time
from pathlib import Path

from benchmarks.assertions import evaluate, parse_cli_output
from benchmarks.execution import run_process


def run_cli(prefix: list[str], case: dict, *, env=None, output_dir: Path | None = None, trace_path=None) -> dict:
    assertion = case["assertion"]
    kind = assertion["kind"]
    screenshot_path = None
    argv = list(prefix)
    if trace_path is not None:
        argv.extend(["--trace-performance", str(trace_path)])
    argv.extend(case["cliArgs"])
    if kind == "screenshot_file":
        if output_dir is None:
            raise ValueError("screenshot_file requires an output directory")
        screenshot_path = output_dir / "capture.png"
        screenshot_path.unlink(missing_ok=True)
        argv.extend(["--output", str(screenshot_path)])
    started_wall_ns = time.time_ns()
    result = run_process(argv, case["timeoutMs"], env)
    operation = parse_cli_output(result["stdout"], result["exit_code"], kind)
    assertion_result = {"assertion_outcome": "not_run"}
    if not result["timed_out"] and operation["operation_outcome"] == "success":
        if kind in {"action_then_element", "wait_then_element"}:
            lookup = run_process(list(prefix) + assertion["verificationCliArgs"], case["timeoutMs"], env)
            parsed = parse_cli_output(lookup["stdout"], lookup["exit_code"], "element_found")
            if parsed["operation_outcome"] == "success":
                assertion_result = evaluate("element_found", parsed["payload"], assertion)
            else:
                assertion_result = {"assertion_outcome": "failed"}
        else:
            assertion_result = evaluate(
                kind, operation["payload"], assertion,
                screenshot_path=screenshot_path, started_ns=started_wall_ns,
            )
    return {
        **result,
        "operation_outcome": operation["operation_outcome"],
        "assertion_outcome": assertion_result["assertion_outcome"],
        "screenshot_path": str(screenshot_path) if screenshot_path else None,
    }
