import json
from pathlib import Path


_ASSERTIONS = {
    "device_info",
    "element_found",
    "action_then_element",
    "wait_then_element",
    "interactive_elements",
    "screenshot_file",
}
_ENVIRONMENT_FIELDS = {
    "build",
    "nativeArtifactVersions",
    "connectionType",
    "targetOS",
    "targetModel",
    "JVM",
    "hostOS",
}


def _argv(value, label, *, allow_empty=False):
    if not isinstance(value, list) or (not value and not allow_empty):
        raise ValueError(f"{label} must be a nonempty argv array")
    if any(not isinstance(arg, str) or not arg for arg in value):
        raise ValueError(f"{label} must contain nonempty strings")


def _validate_environment(environment):
    if not isinstance(environment, dict):
        raise ValueError("environment must be an object")
    missing = _ENVIRONMENT_FIELDS - environment.keys()
    if missing:
        raise ValueError(f"environment is missing fields: {', '.join(sorted(missing))}")
    for name in _ENVIRONMENT_FIELDS:
        entry = environment[name]
        if not isinstance(entry, dict):
            raise ValueError(f"environment.{name} must be an object")
        has_value = "value" in entry and entry["value"] is not None
        has_reason = isinstance(entry.get("unavailableReason"), str) and bool(entry["unavailableReason"].strip())
        if has_value == has_reason:
            raise ValueError(f"environment.{name} must have either value or unavailableReason")


def _validate_case(case, index):
    label = f"cases[{index}]"
    if not isinstance(case, dict):
        raise ValueError(f"{label} must be an object")
    if not isinstance(case.get("id"), str) or not case["id"].strip():
        raise ValueError(f"{label}.id must be a nonempty string")
    setup = case.get("setup")
    if not isinstance(setup, list):
        raise ValueError(f"{label}.setup must be an array of argv arrays")
    for command_index, command in enumerate(setup):
        _argv(command, f"{label}.setup[{command_index}]")
    _argv(case.get("cliArgs"), f"{label}.cliArgs")
    mcp_tool = case.get("mcpTool")
    if not isinstance(mcp_tool, str) or not mcp_tool:
        raise ValueError(f"{label}.mcpTool must be a nonempty string")
    if not isinstance(case.get("mcpArguments"), dict):
        raise ValueError(f"{label}.mcpArguments must be an object")
    timeout = case.get("timeoutMs")
    if isinstance(timeout, bool) or not isinstance(timeout, int) or timeout <= 0:
        raise ValueError(f"{label}.timeoutMs must be a positive integer")
    assertion = case.get("assertion")
    kind = assertion.get("kind") if isinstance(assertion, dict) else None
    if not isinstance(kind, str) or kind not in _ASSERTIONS:
        raise ValueError(f"{label}.assertion.kind is not supported")


def load_manifest(path: Path) -> dict:
    """Load and validate a version-one benchmark manifest."""
    try:
        manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read manifest: {error}") from error
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be an object")
    if isinstance(manifest.get("schemaVersion"), bool) or manifest.get("schemaVersion") != 1:
        raise ValueError("unsupported manifest schemaVersion")
    if manifest.get("platform") not in {"android", "ios"}:
        raise ValueError("platform must be android or ios")
    target = manifest.get("target")
    if not isinstance(target, str) or not target.strip():
        raise ValueError("target must identify exactly one configured device")
    if not isinstance(manifest.get("fixtureRevision"), str) or not manifest["fixtureRevision"].strip():
        raise ValueError("fixtureRevision must be a nonempty string")
    _argv(manifest.get("cliPrefix"), "cliPrefix")
    _argv(manifest.get("mcpPrefix"), "mcpPrefix")
    _validate_environment(manifest.get("environment"))
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases must be a nonempty array")
    ids = set()
    for index, case in enumerate(cases):
        _validate_case(case, index)
        if case["id"] in ids:
            raise ValueError(f"duplicate case id: {case['id']}")
        ids.add(case["id"])
    return manifest
