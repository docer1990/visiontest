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


def _validate_case(case, index, platform):
    label = f"cases[{index}]"
    if not isinstance(case, dict):
        raise ValueError(f"{label} must be an object")
    if not isinstance(case.get("id"), str) or not case["id"].strip():
        raise ValueError(f"{label}.id must be a nonempty string")
    setup = case.get("setup")
    if not isinstance(setup, list) or not setup:
        raise ValueError(f"{label}.setup must install and launch the benchmark fixture reset")
    for command_index, command in enumerate(setup):
        _argv(command, f"{label}.setup[{command_index}]")
        if command[0].rsplit("/", 1)[-1] in {"sh", "bash", "zsh", "cmd", "powershell"}:
            raise ValueError(f"{label}.setup[{command_index}] must not invoke a shell")
    benign = case.get("setupBenignExitCodes", [[] for _ in setup])
    if (not isinstance(benign, list) or len(benign) != len(setup)
            or any(not isinstance(codes, list) or any(isinstance(code, bool) or not isinstance(code, int) or code < 0
                                                       for code in codes) for codes in benign)):
        raise ValueError(f"{label}.setupBenignExitCodes must match setup with nonnegative integer arrays")
    for command, codes in zip(setup, benign):
        documented_terminate = command[:3] == ["xcrun", "simctl", "terminate"] and codes == [3]
        if codes and not documented_terminate:
            raise ValueError(f"{label}.setupBenignExitCodes contains an undocumented benign exit code")
    if setup:
        if platform == "android":
            expected_setup = [
                ["adb", "-s", "{target}", "install", "-r", "{fixtureArtifact}"],
                ["adb", "-s", "{target}", "shell", "am", "force-stop", "{fixtureIdentity}"],
                ["adb", "-s", "{target}", "shell", "am", "start", "-n",
                 "com.example.visiontest.benchmark/.MainActivity",
                 "--es", "scenario", "{scenario}", "--es", "language", "{language}"],
            ]
        else:
            expected_setup = [
                ["xcrun", "simctl", "install", "{target}", "{fixtureArtifact}"],
                ["xcrun", "simctl", "terminate", "{target}", "{fixtureIdentity}"],
                ["xcrun", "simctl", "launch", "{target}", "{fixtureIdentity}", "{scenario}", "{language}"],
            ]
        if setup != expected_setup:
            raise ValueError(f"{label}.setup must use the pinned benchmark fixture setup")
        if case.get("fixtureScenario") not in {"navigation", "appear", "disappear", "missing", "ambiguous", "error"}:
            raise ValueError(f"{label}.fixtureScenario is not supported")
        if case.get("fixtureLanguage") not in {"en", "it"}:
            raise ValueError(f"{label}.fixtureLanguage must be en or it")
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
    required = {
        "device_info": {"platform"},
        "element_found": {"resourceId", "expectedFound"},
        "action_then_element": {"resourceId", "expectedFound", "lookupTool", "lookupArguments", "verificationCliArgs"},
        "wait_then_element": {"resourceId", "expectedFound", "lookupTool", "lookupArguments", "verificationCliArgs"},
        "interactive_elements": {"requiredResourceIds"},
        "screenshot_file": set(),
    }[kind]
    missing = required - assertion.keys()
    if missing:
        raise ValueError(f"{label}.assertion.{kind} is missing fields: {', '.join(sorted(missing))}")
    if kind == "device_info" and assertion["platform"] not in {"android", "ios"}:
        raise ValueError(f"{label}.assertion.platform must be android or ios")
    if kind in {"element_found", "action_then_element", "wait_then_element"}:
        if not isinstance(assertion["resourceId"], str) or not assertion["resourceId"]:
            raise ValueError(f"{label}.assertion.resourceId must be a nonempty string")
        if not isinstance(assertion["expectedFound"], bool):
            raise ValueError(f"{label}.assertion.expectedFound must be a boolean")
    if kind in {"action_then_element", "wait_then_element"}:
        if not isinstance(assertion["lookupTool"], str) or not assertion["lookupTool"]:
            raise ValueError(f"{label}.assertion.lookupTool must be a nonempty string")
        if not isinstance(assertion["lookupArguments"], dict):
            raise ValueError(f"{label}.assertion.lookupArguments must be an object")
        _argv(assertion["verificationCliArgs"], f"{label}.assertion.verificationCliArgs")
    if kind == "action_then_element" and not setup:
        raise ValueError(f"{label}.setup must contain a fixture reset for action_then_element")
    if kind == "interactive_elements":
        resources = assertion["requiredResourceIds"]
        if not isinstance(resources, list) or not resources or any(not isinstance(item, str) or not item for item in resources):
            raise ValueError(f"{label}.assertion.requiredResourceIds must be a nonempty string array")


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
    if manifest.get("fixtureIdentity") != "com.example.visiontest.benchmark":
        raise ValueError("fixtureIdentity must be com.example.visiontest.benchmark")
    if not isinstance(manifest.get("fixtureArtifact"), str) or not manifest["fixtureArtifact"].strip():
        raise ValueError("fixtureArtifact must identify the built benchmark fixture")
    if not isinstance(manifest.get("fixtureRevision"), str) or not manifest["fixtureRevision"].strip():
        raise ValueError("fixtureRevision must be a nonempty string")
    _argv(manifest.get("cliPrefix"), "cliPrefix")
    _argv(manifest.get("mcpPrefix"), "mcpPrefix")
    _validate_environment(manifest.get("environment"))
    process_environment = manifest.get("processEnvironment", {})
    if not isinstance(process_environment, dict) or any(
        not isinstance(key, str) or not key or not isinstance(value, str)
        for key, value in process_environment.items()
    ):
        raise ValueError("processEnvironment must map nonempty names to string values")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases must be a nonempty array")
    ids = set()
    for index, case in enumerate(cases):
        _validate_case(case, index, manifest["platform"])
        if case["id"] in ids:
            raise ValueError(f"duplicate case id: {case['id']}")
        ids.add(case["id"])
    return manifest
