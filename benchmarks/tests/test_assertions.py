import tempfile
import unittest
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

    def test_screenshot_requires_fresh_png_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.png"
            started_ns = path.stat().st_mtime_ns if path.exists() else 0
            path.write_bytes(b"not a png")
            result = evaluate("screenshot_file", {}, {}, screenshot_path=path, started_ns=started_ns)
            self.assertEqual("failed", result["assertion_outcome"])


if __name__ == "__main__":
    unittest.main()
