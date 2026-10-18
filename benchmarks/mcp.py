import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

from benchmarks.assertions import evaluate
from benchmarks.execution import _terminate_group


class McpProtocolError(RuntimeError):
    pass


class McpTimeoutError(McpProtocolError):
    pass


class McpSession:
    PROTOCOL_VERSION = "2024-11-05"

    def __init__(self, argv: list[str], timeout_ms: int, env: dict[str, str] | None = None):
        self.argv = argv
        self.timeout_ms = timeout_ms
        self.env = env
        self.process = None
        self._stdout = queue.Queue()
        self._stderr_parts = []
        self._stderr_lock = threading.Lock()
        self._next_id = 1
        self.calls = []
        self.timings = {"launch_ns": None, "initialize_ns": None, "discovery_ns": None}

    @property
    def stderr(self):
        with self._stderr_lock:
            return "".join(self._stderr_parts)

    def __enter__(self):
        child_env = os.environ.copy()
        if self.env is not None:
            child_env.update(self.env)
        started = time.perf_counter_ns()
        self.process = subprocess.Popen(
            self.argv,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            env=child_env,
            start_new_session=(os.name == "posix"),
        )
        self.timings["launch_ns"] = time.perf_counter_ns() - started
        self._stdout_thread = threading.Thread(target=self._read_stdout, daemon=True)
        self._stderr_thread = threading.Thread(target=self._read_stderr, daemon=True)
        self._stdout_thread.start()
        self._stderr_thread.start()
        return self

    def __exit__(self, exc_type, exc, traceback):
        if self.process is not None and self.process.poll() is None:
            try:
                self.process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
        if self.process is not None:
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                _terminate_group(self.process)
        self._stdout_thread.join(timeout=1)
        self._stderr_thread.join(timeout=1)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()
        return False

    def initialize(self) -> dict:
        started = time.perf_counter_ns()
        result = self._request("initialize", {
            "protocolVersion": self.PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "visiontest-benchmark", "version": "1"},
        })
        if not isinstance(result, dict) or result.get("protocolVersion") != self.PROTOCOL_VERSION:
            raise McpProtocolError("server negotiated an unsupported MCP protocol version")
        self.timings["initialize_ns"] = time.perf_counter_ns() - started
        self._notify("notifications/initialized")
        return result

    def list_tools(self) -> list[dict]:
        started = time.perf_counter_ns()
        tools = []
        cursor = None
        while True:
            params = {} if cursor is None else {"cursor": cursor}
            result = self._request("tools/list", params)
            if not isinstance(result, dict) or not isinstance(result.get("tools"), list):
                raise McpProtocolError("tools/list result has no tools array")
            tools.extend(result["tools"])
            cursor = result.get("nextCursor")
            if cursor is None:
                break
            if not isinstance(cursor, str) or not cursor:
                raise McpProtocolError("tools/list returned an invalid cursor")
        self.timings["discovery_ns"] = time.perf_counter_ns() - started
        return tools

    def call_tool(self, name: str, arguments: dict) -> dict:
        result = self._request("tools/call", {"name": name, "arguments": arguments})
        if not isinstance(result, dict):
            raise McpProtocolError("tools/call result must be an object")
        return result

    def run_case(self, case: dict, *, output_dir: Path | None = None) -> dict:
        assertion = case["assertion"]
        kind = assertion["kind"]
        arguments = dict(case["mcpArguments"])
        screenshot_path = None
        if kind == "screenshot_file":
            if output_dir is None:
                raise ValueError("screenshot_file requires an output directory")
            screenshot_path = output_dir / "capture.png"
            screenshot_path.unlink(missing_ok=True)
            arguments["outputPath"] = str(screenshot_path)
        started_wall_ns = time.time_ns()
        started = time.perf_counter_ns()
        try:
            result = self.call_tool(case["mcpTool"], arguments)
        except McpTimeoutError:
            return {
                "duration_ns": time.perf_counter_ns() - started,
                "exit_code": None, "stdout": "", "stderr": "", "timed_out": True,
                "operation_outcome": "failed", "assertion_outcome": "not_run",
                "screenshot_path": str(screenshot_path) if screenshot_path else None,
            }
        except McpProtocolError as error:
            return {
                "duration_ns": time.perf_counter_ns() - started,
                "exit_code": None, "stdout": "", "stderr": "", "timed_out": False,
                "operation_outcome": "failed", "assertion_outcome": "not_run",
                "screenshot_path": str(screenshot_path) if screenshot_path else None,
                "failure": str(error),
            }
        duration_ns = time.perf_counter_ns() - started
        operation_outcome = "failed" if result.get("isError") is True else "success"
        payload = _public_payload(result)
        if not ((isinstance(payload, dict) and payload) or (isinstance(payload, str) and payload.strip())):
            operation_outcome = "failed"
        if isinstance(payload, dict) and (payload.get("success") is False or "error" in payload):
            operation_outcome = "failed"
        assertion_outcome = "not_run"
        failure = None
        if operation_outcome == "success":
            if kind in {"action_then_element", "wait_then_element"}:
                try:
                    verification = self.call_tool(assertion["lookupTool"], assertion["lookupArguments"])
                    verify_payload = _public_payload(verification)
                except McpProtocolError as error:
                    verification = {"isError": True}
                    verify_payload = None
                    verification_failure = str(error)
                else:
                    verification_failure = None
                if verification.get("isError") is True or not isinstance(verify_payload, dict):
                    assertion_outcome = "failed"
                    failure = verification_failure
                else:
                    assertion_outcome = evaluate("element_found", verify_payload, assertion)["assertion_outcome"]
            elif kind == "screenshot_file":
                assertion_outcome = evaluate(
                    kind, {}, assertion, screenshot_path=screenshot_path, started_ns=started_wall_ns
                )["assertion_outcome"]
            elif isinstance(payload, dict):
                assertion_outcome = evaluate(kind, payload, assertion)["assertion_outcome"]
            else:
                operation_outcome = "failed"
        return {
            "duration_ns": duration_ns,
            "exit_code": None,
            "stdout": "",
            "stderr": "",
            "timed_out": False,
            "operation_outcome": operation_outcome,
            "assertion_outcome": assertion_outcome,
            "screenshot_path": str(screenshot_path) if screenshot_path else None,
            "failure": failure,
        }

    def _request(self, method, params):
        if self.process is None or self.process.poll() is not None:
            raise McpProtocolError("MCP process is not running")
        request_id = self._next_id
        self._next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        if method == 'tools/call':
            self.calls.append(params['name'])
        response = self._read_response()
        while "method" in response and "id" not in response:
            response = self._read_response()
        if response.get("id") != request_id:
            raise McpProtocolError(f"unmatched response id: expected {request_id}, received {response.get('id')}")
        if "error" in response:
            error = response["error"]
            message = error.get("message", "unknown error") if isinstance(error, dict) else str(error)
            raise McpProtocolError(f"JSON-RPC {method} failed: {message}")
        if "result" not in response:
            raise McpProtocolError(f"malformed JSON-RPC response for {method}: missing result")
        return response["result"]

    def _notify(self, method):
        self._send({"jsonrpc": "2.0", "method": method})

    def _send(self, message):
        try:
            self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            raise McpProtocolError(f"cannot write MCP request: {error}") from error

    def _read_response(self):
        try:
            line = self._stdout.get(timeout=self.timeout_ms / 1000)
        except queue.Empty as error:
            _terminate_group(self.process)
            raise McpTimeoutError("timed out waiting for MCP response") from error
        if line is None:
            raise McpProtocolError("MCP process closed stdout before responding")
        try:
            response = json.loads(line)
        except json.JSONDecodeError as error:
            raise McpProtocolError(f"malformed MCP JSON line: {line[:160]!r}") from error
        if not isinstance(response, dict) or response.get("jsonrpc") != "2.0":
            raise McpProtocolError("malformed JSON-RPC response")
        return response

    def _read_stdout(self):
        try:
            for line in self.process.stdout:
                self._stdout.put(line.rstrip("\r\n"))
        finally:
            self._stdout.put(None)

    def _read_stderr(self):
        while True:
            chunk = self.process.stderr.buffer.read1(4096)
            if not chunk:
                return
            with self._stderr_lock:
                self._stderr_parts.append(chunk.decode("utf-8", errors="replace"))


def _public_payload(result):
    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        return structured
    content = result.get("content")
    if not isinstance(content, list):
        return None
    text_items = [item.get("text") for item in content if isinstance(item, dict) and item.get("type") == "text"]
    if len(text_items) != 1 or not isinstance(text_items[0], str):
        return None
    try:
        return json.loads(text_items[0])
    except json.JSONDecodeError:
        return text_items[0]
