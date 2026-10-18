import json
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def parse_cli_output(stdout: str, exit_code: int | None, kind: str) -> dict:
    if exit_code is None or exit_code != 0:
        return {"operation_outcome": "failed", "assertion_outcome": "not_run", "payload": None}
    try:
        payload = json.loads(stdout)
    except (TypeError, json.JSONDecodeError):
        if kind in {"action_then_element", "wait_then_element", "screenshot_file"}:
            return {"operation_outcome": "success", "assertion_outcome": "not_run", "payload": None}
        return {"operation_outcome": "failed", "assertion_outcome": "not_run", "payload": None}
    if isinstance(payload, dict) and ("error" in payload or payload.get("success") is False):
        return {"operation_outcome": "failed", "assertion_outcome": "not_run", "payload": payload}
    if kind in {"action_then_element", "wait_then_element", "screenshot_file"}:
        return {"operation_outcome": "success", "assertion_outcome": "not_run", "payload": payload}
    if not isinstance(payload, dict):
        return {"operation_outcome": "failed", "assertion_outcome": "not_run", "payload": payload}
    return {"operation_outcome": "success", "assertion_outcome": "not_run", "payload": payload}


def evaluate(kind: str, payload: dict | None, assertion: dict, *, screenshot_path=None, started_ns=None) -> dict:
    operation = "success"
    passed = False
    if isinstance(payload, dict) and (payload.get("success") is False or "error" in payload):
        operation = "failed"
    elif kind == "screenshot_file":
        passed = _fresh_png(screenshot_path, started_ns)
    elif not isinstance(payload, dict):
        operation = "failed"
    elif kind == "device_info":
        passed = (
            payload.get("success") is True
            and isinstance(payload.get("displayWidth"), int)
            and isinstance(payload.get("displayHeight"), int)
            and isinstance(payload.get("productName"), str)
            and ("sdkVersion" in payload if assertion["platform"] == "android" else "osVersion" in payload)
        )
    elif kind == "element_found":
        passed = payload.get("found") is assertion["expectedFound"]
        if payload.get("found") is True:
            passed = passed and payload.get("resourceId") == assertion["resourceId"]
    elif kind in {"action_then_element", "wait_then_element"}:
        passed = payload.get("found") is assertion["expectedFound"]
        if payload.get("found") is True:
            passed = passed and payload.get("resourceId") == assertion["resourceId"]
    elif kind == "interactive_elements":
        elements = payload.get("elements")
        if payload.get("success") is True and isinstance(elements, list):
            found = {item.get("resourceId") for item in elements if isinstance(item, dict)}
            passed = all(resource_id in found for resource_id in assertion["requiredResourceIds"])
    else:
        operation = "failed"
    return {
        "operation_outcome": operation,
        "assertion_outcome": "passed" if operation == "success" and passed else "failed",
    }


def _fresh_png(path, started_ns):
    if path is None:
        return False
    try:
        file_path = Path(path)
        return file_path.stat().st_mtime_ns >= started_ns and file_path.read_bytes()[:8] == PNG_SIGNATURE
    except (OSError, TypeError):
        return False
