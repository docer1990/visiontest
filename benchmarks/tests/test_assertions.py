import json
import tempfile
import unittest
import os
from pathlib import Path

from benchmarks.assertions import evaluate, parse_cli_output


class AssertionsTest(unittest.TestCase):
    def test_failed_success_flag_is_operation_failure(self):
        result = evaluate("device_info", {"success": False}, {"platform": "android"})
        self.assertEqual("failed", result["operation_outcome"])

    def test_element_assertion_failure_is_separate(self):
        result = evaluate("element_found", {"found": False}, {
            "resourceId": "continue", "expectedFound": True,
        })
        self.assertEqual("success", result["operation_outcome"])
        self.assertEqual("failed", result["assertion_outcome"])

    def test_malformed_cli_output_fails_operation(self):
        result = parse_cli_output("not structured", 0, "device_info")
        self.assertEqual("failed", result["operation_outcome"])

    def test_json_operation_failure_is_not_hidden_for_action_commands(self):
        result = parse_cli_output('{"success":false}', 0, "action_then_element")
        self.assertEqual("failed", result["operation_outcome"])

    def test_cli_verification_commands_require_usable_nonempty_output(self):
        for kind in ("action_then_element", "wait_then_element", "screenshot_file"):
            for stdout in ("", " \n\t", "{}", "null", "[]", "[{}]", "true", "false", "0", '""', '"done"'):
                with self.subTest(kind=kind, stdout=stdout):
                    result = parse_cli_output(stdout, 0, kind)
                    self.assertEqual("failed", result["operation_outcome"])
                    self.assertEqual("not_run", result["assertion_outcome"])

    def test_cli_verification_commands_accept_public_text_and_objects(self):
        for kind, stdout in (
            ("action_then_element", '{"success":true}'),
            ("action_then_element", '{"result":{"success":true}}'),
            ("action_then_element", "Tapped element"),
            ("wait_then_element", '{"found":true,"resourceId":"done"}'),
            ("wait_then_element", "Element is no longer present (resourceId='loading')."),
            ("screenshot_file", "Screenshot saved to /tmp/capture.png"),
        ):
            with self.subTest(kind=kind, stdout=stdout):
                self.assertEqual("success", parse_cli_output(stdout, 0, kind)["operation_outcome"])

    def test_cli_verification_commands_preserve_structured_operation_failures(self):
        for kind in ("action_then_element", "wait_then_element", "screenshot_file"):
            for stdout in ('{"success":false}', '{"error":{"message":"unavailable"}}'):
                with self.subTest(kind=kind, stdout=stdout):
                    self.assertEqual("failed", parse_cli_output(stdout, 0, kind)["operation_outcome"])

    def test_cli_native_envelopes_are_validated_before_operation_checks(self):
        invalid = [
            {"jsonrpc": "2.0", "id": 1, "result": {"success": False}},
            {"jsonrpc": "2.0", "id": 1, "result": {"error": "failed"}},
            {"jsonrpc": "1.0", "id": 1, "result": {"success": True}},
            {"jsonrpc": "2.0", "result": {"success": True}},
            {"jsonrpc": "2.0", "id": 1, "result": {}},
            {"jsonrpc": "2.0", "id": 1, "result": []},
            {"jsonrpc": "2.0", "id": 1, "error": {}, "result": {"success": True}},
        ]
        for kind in ("action_then_element", "wait_then_element", "screenshot_file"):
            for payload in invalid:
                with self.subTest(kind=kind, payload=payload):
                    result = parse_cli_output(json.dumps(payload), 0, kind)
                    self.assertEqual("failed", result["operation_outcome"])
                    self.assertEqual("not_run", result["assertion_outcome"])
        payload = {"success": True, "result": {"ordinary": "field"}}
        envelope = {"jsonrpc": "2.0", "id": 1, "result": payload}
        self.assertEqual(payload, parse_cli_output(json.dumps(envelope), 0, "action_then_element")["payload"])
        self.assertEqual(payload, parse_cli_output(json.dumps(payload), 0, "action_then_element")["payload"])

    def test_screenshot_requires_fresh_png_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.png"
            started_ns = path.stat().st_mtime_ns if path.exists() else 0
            path.write_bytes(b"not a png")
            result = evaluate("screenshot_file", {}, {}, screenshot_path=path, started_ns=started_ns)
            self.assertEqual("failed", result["assertion_outcome"])

    def test_screenshot_rejects_stale_file_without_structured_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.png"
            path.write_bytes(b"\x89PNG\r\n\x1a\n")
            os.utime(path, ns=(1, 1))
            result = evaluate("screenshot_file", None, {}, screenshot_path=path, started_ns=2)
            self.assertEqual("failed", result["assertion_outcome"])

    def test_screenshot_error_payload_cannot_pass_with_a_fresh_png(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.png"
            path.write_bytes(b"\x89PNG\r\n\x1a\n")
            result = evaluate("screenshot_file", {"success": False}, {},
                              screenshot_path=path, started_ns=0)
            self.assertEqual("failed", result["operation_outcome"])
            self.assertEqual("failed", result["assertion_outcome"])


if __name__ == "__main__":
    unittest.main()
