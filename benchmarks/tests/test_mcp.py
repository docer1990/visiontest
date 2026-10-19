import sys
import time
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from benchmarks.mcp import McpProtocolError, McpSession, _public_payload


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

    def test_orderly_exit_sends_eof_before_terminating_server(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'closed'
            program = 'import sys,time,pathlib; sys.stdin.read(); time.sleep(.05); pathlib.Path(sys.argv[1]).write_text("EOF")'
            with McpSession([sys.executable, '-c', program, str(marker)], 1000):
                pass
            self.assertTrue(marker.exists(), 'server never received orderly EOF')
            self.assertEqual('EOF', marker.read_text())

    def test_dispatch_ledger_counts_verification_calls_separately(self):
        case = {'id': 'action', 'mcpTool': 'info', 'mcpArguments': {},
                'assertion': {'kind': 'action_then_element', 'lookupTool': 'second',
                              'lookupArguments': {}, 'resourceId': 'done', 'expectedFound': True}}
        with McpSession(command(), 2000) as session:
            session.initialize()
            session.list_tools()
            session.run_case(case)
            self.assertEqual(['info', 'second'], getattr(session, 'calls', None))

    def test_action_wait_and_screenshot_reject_unusable_operation_results(self):
        malformed = [
            {}, {"content": []}, {"content": None},
            {"content": [{"type": "text", "text": ""}]},
            {"content": [{"type": "text", "text": "   "}]},
            {"content": [{"type": "text", "text": 7}]},
            {"content": [{"type": "image", "data": "ignored"}]},
            {"structuredContent": {}},
            {"content": [{"type": "text", "text": "null"}]},
            {"content": [{"type": "text", "text": "[]"}]},
            {"structuredContent": {"success": False}},
            {"structuredContent": {"error": "failed"}},
            {"isError": True, "content": [{"type": "text", "text": "done"}]},
        ]
        for kind in ("action_then_element", "wait_then_element", "screenshot_file"):
            for response in malformed:
                with self.subTest(kind=kind, response=response), tempfile.TemporaryDirectory() as directory:
                    case = {"id": "operation", "mcpTool": "operation", "mcpArguments": {},
                            "assertion": {"kind": kind, "lookupTool": "lookup", "lookupArguments": {},
                                          "resourceId": "done", "expectedFound": True}}
                    session = McpSession(command(), 2000)
                    verification = {"structuredContent": {"found": True, "resourceId": "done"}}
                    with patch.object(session, "call_tool", side_effect=[response, verification]) as call:
                        result = session.run_case(case, output_dir=Path(directory))
                    self.assertEqual("failed", result["operation_outcome"])
                    self.assertEqual("not_run", result["assertion_outcome"])
                    self.assertEqual(1, call.call_count)

    def test_action_and_wait_accept_nonempty_public_text_before_verification(self):
        for kind in ("action_then_element", "wait_then_element"):
            with self.subTest(kind=kind):
                case = {"id": "operation", "mcpTool": "operation", "mcpArguments": {},
                        "assertion": {"kind": kind, "lookupTool": "lookup", "lookupArguments": {},
                                      "resourceId": "done", "expectedFound": True}}
                session = McpSession(command(), 2000)
                with patch.object(session, "call_tool", side_effect=[
                    {"content": [{"type": "text", "text": "Operation completed"}]},
                    {"structuredContent": {"found": True, "resourceId": "done"}},
                ]):
                    result = session.run_case(case)
                self.assertEqual("success", result["operation_outcome"])
                self.assertEqual("passed", result["assertion_outcome"])

    def test_native_envelope_device_and_element_results_are_evaluated(self):
        cases = [
            ({"kind": "device_info", "platform": "ios"},
             {"displayHeight": 874, "displayRotation": 0, "displayWidth": 402,
              "osVersion": "26.5", "productName": "iPhone", "success": True}),
            ({"kind": "element_found", "resourceId": "done", "expectedFound": True},
             {"found": True, "resourceId": "done"}),
        ]
        for assertion, payload in cases:
            for structured in (False, True):
                with self.subTest(kind=assertion["kind"], structured=structured):
                    envelope = {"id": 1, "jsonrpc": "2.0", "result": payload}
                    response = ({"structuredContent": envelope} if structured else
                                {"content": [{"type": "text", "text": json.dumps(envelope)}],
                                 "isError": False, "_meta": {}})
                    case = {"id": "native", "mcpTool": "native", "mcpArguments": {},
                            "assertion": assertion}
                    session = McpSession(command(), 2000)
                    with patch.object(session, "call_tool", return_value=response):
                        result = session.run_case(case)
                    self.assertEqual("success", result["operation_outcome"])
                    self.assertEqual("passed", result["assertion_outcome"])

    def test_native_failures_and_malformed_envelopes_skip_action_verification(self):
        envelopes = [
            {"id": 1, "jsonrpc": "2.0", "result": {"success": False}},
            {"id": 1, "jsonrpc": "2.0", "result": {"error": "native failed"}},
            {"id": 1, "jsonrpc": "2.0", "error": {"code": -32000, "message": "native failed"}},
            {"id": 1, "jsonrpc": "2.0", "error": {}, "result": {"success": True}},
            {"id": 1, "jsonrpc": "2.0"},
            {"jsonrpc": "2.0", "result": {"success": True}},
            {"id": 1, "jsonrpc": "1.0", "result": {"success": True}},
            {"id": 1, "jsonrpc": "2.0", "result": None},
            {"id": 1, "jsonrpc": "2.0", "result": []},
            {"id": 1, "jsonrpc": "2.0", "result": "invalid native result"},
            {"id": 1, "jsonrpc": "2.0", "result": {}},
        ]
        for envelope in envelopes:
            with self.subTest(envelope=envelope):
                case = {"id": "action", "mcpTool": "action", "mcpArguments": {},
                        "assertion": {"kind": "action_then_element", "lookupTool": "lookup",
                                      "lookupArguments": {}, "resourceId": "done", "expectedFound": True}}
                session = McpSession(command(), 2000)
                with patch.object(session, "call_tool", side_effect=[
                    {"content": [{"type": "text", "text": json.dumps(envelope)}]},
                    {"structuredContent": {"found": True, "resourceId": "done"}},
                ]) as call:
                    result = session.run_case(case)
                self.assertEqual("failed", result["operation_outcome"])
                self.assertEqual("not_run", result["assertion_outcome"])
                self.assertEqual(1, call.call_count)

    def test_payload_normalization_does_not_unwrap_arbitrary_result_fields(self):
        payload = {"result": {"success": True}, "found": False}
        self.assertEqual(payload, _public_payload({"structuredContent": payload}))
        self.assertEqual(payload, _public_payload({"content": [{"type": "text", "text": json.dumps(payload)}]}))

    def test_newline_framing_and_call_error_are_preserved(self):
        with McpSession(command(), 2000) as session:
            session.initialize()
            result = session.call_tool("first", {})
            self.assertIn("content", result)
            self.assertFalse(result.get("isError", False))


if __name__ == "__main__":
    unittest.main()
