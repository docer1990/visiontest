import sys
import time
import json
import unittest
from pathlib import Path

from benchmarks.mcp import McpProtocolError, McpSession


FAKE = Path(__file__).with_name("fake_mcp.py")


def command(mode="normal"):
    return [sys.executable, str(FAKE), mode]


class McpTest(unittest.TestCase):
    def test_handshake_paginates_tools_and_warm_calls_reuse_process(self):
        with McpSession(command(), 2000) as session:
            initialized = session.initialize()
            self.assertEqual("2024-11-05", initialized["protocolVersion"])
            tools = session.list_tools()
            self.assertEqual(["first", "second"], [tool["name"] for tool in tools])
            first = session.call_tool("first", {"x": 1})
            second = session.call_tool("second", {})
            first_payload = json.loads(first["content"][0]["text"])
            second_payload = json.loads(second["content"][0]["text"])
            self.assertEqual(first_payload["pid"], second_payload["pid"])
            self.assertEqual({"x": 1}, first_payload["arguments"])

    def test_failed_initialization_is_reported(self):
        with McpSession(command("failed-init"), 2000) as session:
            with self.assertRaisesRegex(McpProtocolError, "init failed"):
                session.initialize()

    def test_unmatched_response_id_is_rejected(self):
        with McpSession(command("unmatched"), 2000) as session:
            with self.assertRaisesRegex(McpProtocolError, "unmatched"):
                session.initialize()

    def test_malformed_response_is_rejected(self):
        with McpSession(command("malformed"), 2000) as session:
            with self.assertRaisesRegex(McpProtocolError, "malformed"):
                session.initialize()

    def test_stderr_is_drained_during_protocol_use(self):
        with McpSession(command("stderr"), 2000) as session:
            session.initialize()
            deadline = time.monotonic() + 1
            while len(session.stderr) < 200000 and time.monotonic() < deadline:
                time.sleep(0.005)
            self.assertEqual(200000, len(session.stderr))

    def test_initialize_timeout_terminates_server(self):
        with McpSession(command("silent"), 50) as session:
            with self.assertRaisesRegex(McpProtocolError, "timed out"):
                session.initialize()
            self.assertIsNotNone(session.process.poll())

    def test_server_notifications_do_not_steal_response_routing(self):
        with McpSession(command("notification"), 2000) as session:
            session.initialize()
            self.assertEqual(["first", "second"], [tool["name"] for tool in session.list_tools()])

    def test_unsupported_negotiated_protocol_is_rejected(self):
        with McpSession(command("wrong-version"), 2000) as session:
            with self.assertRaisesRegex(McpProtocolError, "unsupported MCP protocol"):
                session.initialize()

    def test_run_case_parses_public_text_result_and_checks_mcp_error(self):
        case = {"id": "info", "mcpTool": "info", "mcpArguments": {},
                "timeoutMs": 2000, "assertion": {"kind": "device_info", "platform": "android"}}
        with McpSession(command(), 2000) as session:
            session.initialize()
            passed = session.run_case(case)
            self.assertEqual("success", passed["operation_outcome"])
            self.assertEqual("passed", passed["assertion_outcome"])
        case["mcpTool"] = "fail"
        with McpSession(command("tool-error"), 2000) as session:
            session.initialize()
            failed = session.run_case(case)
            self.assertEqual("failed", failed["operation_outcome"])

    def test_timed_out_tool_call_is_uncertain_without_retry(self):
        case = {"id": "info", "mcpTool": "info", "mcpArguments": {}, "timeoutMs": 50,
                "assertion": {"kind": "device_info", "platform": "android"}}
        with McpSession(command("call-silent"), 50) as session:
            session.initialize()
            result = session.run_case(case)
            self.assertTrue(result["timed_out"])
            self.assertEqual(2, session._next_id - 1)

    def test_newline_framing_and_call_error_are_preserved(self):
        with McpSession(command(), 2000) as session:
            session.initialize()
            result = session.call_tool("first", {})
            self.assertIn("content", result)
            self.assertFalse(result.get("isError", False))


if __name__ == "__main__":
    unittest.main()
