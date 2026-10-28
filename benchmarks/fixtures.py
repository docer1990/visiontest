import re
import json
import math
import time

from benchmarks.execution import run_process


_TOKEN = re.compile(r"^\{([a-z][A-Za-z0-9]*)\}$")


def validate_target(platform: str, selected: str, available: list[str]) -> None:
    if platform not in {"android", "ios"}:
        raise ValueError("platform must be android or ios")
    if not selected or not isinstance(selected, str):
        raise ValueError("selected target is required")
    active = list(dict.fromkeys(available))
    if len(active) != 1 or active[0] != selected:
        raise ValueError(f"expected only selected {platform} target {selected}; found {active}")


def resolve_argv(argv: list[str], tokens: dict[str, str]) -> list[str]:
    resolved = []
    for value in argv:
        match = _TOKEN.fullmatch(value)
        if "{" in value or "}" in value:
            if match is None or match.group(1) not in tokens:
                raise ValueError(f"unsupported or embedded manifest token: {value}")
            value = tokens[match.group(1)]
        resolved.append(value)
    return resolved


def validate_fixture_identity(platform: str, identity: str) -> None:
    expected = "dev.visiontest.benchmark"
    if platform not in {"android", "ios"} or identity != expected:
        raise ValueError("fixture identity must be dev.visiontest.benchmark")


def selector_resource_id(platform: str, identity: str, name: str) -> str:
    if platform not in {"android", "ios"}:
        raise ValueError("platform must be android or ios")
    if not isinstance(identity, str) or not identity or not isinstance(name, str) or not name:
        raise ValueError("fixture identity and selector name are required")
    return f"{identity}:id/{name}" if platform == "android" else name


def wait_fixture_ready(case: dict, manifest: dict, env: dict, *, runner=None) -> tuple[bool, str | None]:
    runner = runner or run_process
    platform = manifest["platform"]
    identity = manifest["fixtureIdentity"]
    scenario = case["fixtureScenario"]
    checks = [("fixture_ready", True), ("start", True)] + {
        "navigation": [("email", True), ("continue_action" if platform == "android" else "continue", True)],
        "appear": [("done", True)],
        "disappear": [("loading", False)],
        "missing": [("email", False)],
        "ambiguous": [("continue_primary", True), ("continue_secondary", True)],
        "error": [("error", True)],
    }[scenario]
    budget_ms = case["timeoutMs"]
    deadline = time.monotonic() + budget_ms / 1000
    probe_env = {**env, "VISIONTEST_TRACE_PERFORMANCE": ""}
    while time.monotonic() < deadline:
        ready = True
        for name, expected in checks:
            remaining_ms = math.ceil((deadline - time.monotonic()) * 1000)
            if remaining_ms <= 0:
                ready = False
                break
            resource_id = selector_resource_id(platform, identity, name)
            argv = manifest["cliPrefix"] + ["find_element", "--resource-id", resource_id,
                                           "--json", "--platform", platform]
            if platform == "ios":
                argv += ["--bundle-id", identity]
            result = runner(argv, timeout_ms=remaining_ms, env=probe_env)
            if result["timed_out"] or result["exit_code"] != 0:
                return False, f"fixture readiness probe failed for {name} (exit {result['exit_code']})"
            try:
                payload = json.loads(result["stdout"])
            except (TypeError, json.JSONDecodeError):
                return False, f"fixture readiness probe returned invalid JSON for {name}"
            if (not isinstance(payload, dict) or type(payload.get("found")) is not bool
                    or payload.get("success") is False or "error" in payload
                    or (payload["found"] and payload.get("resourceId") != resource_id)):
                return False, f"fixture readiness probe returned invalid result for {name}"
            if payload["found"] is not expected:
                ready = False
                break
        if ready and time.monotonic() <= deadline:
            return True, None
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(0.1, remaining))
    return False, f"fixture readiness for {scenario} timed out after {budget_ms} ms"
