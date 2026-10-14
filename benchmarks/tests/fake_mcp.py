import json
import os
import sys


mode = sys.argv[1] if len(sys.argv) > 1 else "normal"
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
                        "result": {"tools": [{"name": "first"}], "nextCursor": "page-2"}}
    elif method == "tools/call":
        if mode == "call-silent":
            continue
        tool_name = request["params"]["name"]
        payload = ({"success": True, "displayWidth": 100, "displayHeight": 200,
                    "productName": "fake", "sdkVersion": 35}
                   if tool_name == "info" else
                   {"pid": os.getpid(), "arguments": request["params"]["arguments"]})
        response = {"jsonrpc": "2.0", "id": request_id, "result": {
            "content": [{"type": "text", "text": json.dumps(payload)}]}}
        if mode == "tool-error":
            response["result"]["isError"] = True
    else:
        response = {"jsonrpc": "2.0", "id": request_id, "result": {}}
    if mode == "unmatched" and method == "initialize":
        response["id"] = request_id + 100
    print(json.dumps(response), flush=True)
