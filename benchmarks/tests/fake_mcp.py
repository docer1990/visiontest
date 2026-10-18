import json
import os
import sys
import uuid
from pathlib import Path


mode = sys.argv[1] if len(sys.argv) > 1 else "normal"
trace_path = os.environ.get('VISIONTEST_TRACE_PERFORMANCE') if mode == 'trace' else None
trace_rows = []
session_id = uuid.uuid4().hex
invocation_count = 0
if trace_path:
    trace_rows.append({'type': 'session', 'schemaVersion': 1, 'sessionId': session_id,
                       'mode': 'mcp', 'originNs': 0, 'visionTestVersion': 'fake'})
if mode == "stderr":
    sys.stderr.write("x" * 200000)
    sys.stderr.flush()

for line in sys.stdin:
    try:
        request = json.loads(line)
    except json.JSONDecodeError:
        print("{malformed", flush=True)
        continue
    method = request.get("method")
    request_id = request.get("id")
    if mode == "malformed" and method == "initialize":
        print("{malformed", flush=True)
        continue
    if mode == "silent" and method == "initialize":
        continue
    if method == "initialize":
        if mode == "failed-init":
            response = {"jsonrpc": "2.0", "id": request_id,
                        "error": {"code": -32000, "message": "init failed"}}
        else:
            version = "1999-01-01" if mode == "wrong-version" else "2024-11-05"
            response = {"jsonrpc": "2.0", "id": request_id, "result": {
                "protocolVersion": version, "capabilities": {"tools": {}},
                "serverInfo": {"name": "fake", "version": "1"}}}
    elif method == "notifications/initialized":
        continue
    elif method == "tools/list":
        if mode == "notification":
            print(json.dumps({"jsonrpc": "2.0", "method": "notifications/message", "params": {}}), flush=True)
        cursor = request.get("params", {}).get("cursor")
        if cursor:
            response = {"jsonrpc": "2.0", "id": request_id,
                        "result": {"tools": [{"name": "second"}]}}
        else:
            response = {"jsonrpc": "2.0", "id": request_id,
                        "result": {"tools": [{"name": "info" if mode == "trace" else "first"}], "nextCursor": "page-2"}}
    elif method == "tools/call":
        if mode == "call-silent":
            continue
        tool_name = request["params"]["name"]
        payload = ({"success": True, "displayWidth": 100, "displayHeight": 200,
                    "productName": "fake", "sdkVersion": 35}
                   if tool_name == "info" else
                   {"pid": os.getpid(), "arguments": request["params"]["arguments"]})
        if mode == 'trace' and tool_name == 'second':
            payload = {'found': True, 'resourceId': request['params']['arguments'].get('resourceId')}
        if trace_path:
            invocation_count += 1
            invocation_id = uuid.uuid4().hex
            trace_rows.append({'sessionId': session_id, 'invocationId': invocation_id,
                               'invocationSequence': invocation_count, 'spanId': uuid.uuid4().hex,
                               'parentSpanId': None, 'process': 'host', 'platform': 'android',
                               'operation': tool_name, 'stage': 'invocation', 'startOffsetNs': 0,
                               'durationNs': 1, 'outcome': 'returned', 'operationOutcome': 'unknown'})
        response = {"jsonrpc": "2.0", "id": request_id, "result": {
            "content": [{"type": "text", "text": json.dumps(payload)}]}}
        if mode.startswith("screenshot"):
            Path(request["params"]["arguments"]["outputPath"]).write_bytes(bytes([137,80,78,71,13,10,26,10]))
            response["result"] = {"content": [{"type": "text", "text": "Screenshot saved"}]}
        if mode in {"tool-error", "screenshot-error"}:
            response["result"]["isError"] = True
    else:
        response = {"jsonrpc": "2.0", "id": request_id, "result": {}}
    if mode == "unmatched" and method == "initialize":
        response["id"] = request_id + 100
    print(json.dumps(response), flush=True)

if trace_path:
    trace_rows.append({'type': 'session.end', 'sessionId': session_id, 'written': len(trace_rows),
                       'dropped': 0, 'invocationsStarted': invocation_count,
                       'invocationsCompleted': invocation_count, 'complete': True})
    Path(trace_path).write_text(''.join(json.dumps(row) + '\n' for row in trace_rows))
