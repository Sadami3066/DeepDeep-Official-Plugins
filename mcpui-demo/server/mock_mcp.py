#!/usr/bin/env python3
"""Minimal streamable-HTTP MCP server for the MCP UI demo.

Run: python3 mock_mcp.py [port]
MCP URL: http://<host>:<port>/mcp (emulator: http://10.0.2.2:<port>/mcp)
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
SESSION_ID = "demo-session"
PROTOCOL = "2025-03-26"
TOOLS = [
    {
        "name": "weather",
        "description": "Get a tiny weather sample for a city.",
        "inputSchema": {
            "type": "object",
            "properties": {"city": {"type": "string"}},
            "additionalProperties": False,
        },
    }
]


def tool_result(city):
    text = f"{city} 26°C 晴，最高 29°，最低 20°"
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": {
            "city": city,
            "temperature": 26,
            "condition": "晴",
            "high": 29,
            "low": 20,
        },
        "isError": False,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, _format, *_args):
        pass

    def _send(self, status, payload=None):
        body = b"" if payload is None else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Mcp-Session-Id", SESSION_ID)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        self._send(405)

    def do_DELETE(self):
        self._send(204)

    def do_POST(self):
        if self.path.rstrip("/") != "/mcp":
            self._send(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            message = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._send(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "invalid json"}})
            return
        if isinstance(message, list):
            responses = [self._handle(item) for item in message]
            self._send(200, responses)
            return
        response = self._handle(message)
        if response is None:
            self._send(202)
        else:
            self._send(200, response)

    def _handle(self, message):
        method = message.get("method")
        request_id = message.get("id")
        if method is None:
            return None
        if method == "initialize":
            return self._result(request_id, {
                "protocolVersion": PROTOCOL,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "mcp-ui-demo", "version": "0.1.0"},
            })
        if method == "tools/list":
            return self._result(request_id, {"tools": TOOLS})
        if method == "tools/call":
            params = message.get("params") or {}
            if params.get("name") != "weather":
                return self._error(request_id, -32602, "unknown tool")
            arguments = params.get("arguments") or {}
            return self._result(request_id, tool_result(str(arguments.get("city") or "杭州")))
        if method == "ping":
            return self._result(request_id, {})
        if request_id is None:
            return None
        return self._error(request_id, -32601, f"unknown method: {method}")

    def _result(self, request_id, result):
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _error(self, request_id, code, message):
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


if __name__ == "__main__":
    print(f"MCP demo server: http://127.0.0.1:{PORT}/mcp", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
