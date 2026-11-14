#!/usr/bin/env python3
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
SESSION_ID = "hotel-demo-session"
PROTOCOL = "2025-03-26"
TOOLS = [
    {
        "name": "searchHotels",
        "description": "Search hotels near a place.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "originQuery": {"type": "string"},
                "place": {"type": "string"},
                "placeType": {"type": "string"},
                "size": {"type": "integer"},
            },
            "required": ["place"],
            "additionalProperties": True,
        },
    },
    {
        "name": "getHotelDetail",
        "description": "Get room details for a hotel.",
        "inputSchema": {
            "type": "object",
            "properties": {"hotelId": {"type": "integer"}, "name": {"type": "string"}},
            "additionalProperties": True,
        },
    },
    {
        "name": "getHotelSearchTags",
        "description": "Hotel search tags.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]

HOTELS = [
    {"name": "杭州西湖国宾馆", "star": 5.0, "price": 1288, "address": "西湖区杨公堤18号", "distance": "距西湖 900m", "tags": ["湖景", "亲子", "免费停车"]},
    {"name": "杭州西子湖四季酒店", "star": 5.0, "price": 2680, "address": "西湖区灵隐路5号", "distance": "距西湖 1.2km", "tags": ["湖景", "泳池", "SPA"]},
    {"name": "杭州君悦酒店", "star": 5.0, "price": 1580, "address": "上城区湖滨街道湖滨路28号", "distance": "距西湖 400m", "tags": ["湖景", "地铁", "早餐"]},
    {"name": "杭州西湖希尔顿嘉悦里", "star": 4.0, "price": 980, "address": "上城区西湖大道18号", "distance": "距西湖 1.1km", "tags": ["地铁", "健身", "亲子"]},
    {"name": "杭州湖滨银泰亚朵酒店", "star": 4.0, "price": 620, "address": "上城区延安路98号", "distance": "距西湖 700m", "tags": ["地铁", "性价比", "早餐"]},
]


def search_result(args):
    place = str(args.get("place") or "杭州西湖")
    size = int(args.get("size") or 5)
    hotels = HOTELS[: max(1, min(size, len(HOTELS)))]
    text = "\n".join(f"{h['name']} | {h['star']}星 | ¥{h['price']} | {h['address']}" for h in hotels)
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": {"place": place, "hotels": hotels},
        "isError": False,
    }


def detail_result(args):
    name = str(args.get("name") or "杭州西湖国宾馆")
    rooms = [
        {"name": "园景大床房", "price": 1288, "cancelPolicy": "入住前一天18:00前免费取消"},
        {"name": "湖景双床房", "price": 1580, "cancelPolicy": "不可取消"},
        {"name": "行政套房", "price": 2680, "cancelPolicy": "入住前一天18:00前免费取消"},
    ]
    text = "\n".join(f"{r['name']} | ¥{r['price']} | {r['cancelPolicy']}" for r in rooms)
    return {
        "content": [{"type": "text", "text": text}],
        "structuredContent": {"hotelName": name, "rooms": rooms},
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
            self._send(200, [self._handle(item) for item in message])
            return
        response = self._handle(message)
        self._send(202 if response is None else 200, response)

    def _handle(self, message):
        method = message.get("method")
        request_id = message.get("id")
        if method == "initialize":
            return self._result(request_id, {
                "protocolVersion": PROTOCOL,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "rollinggo-hotel-demo", "version": "0.1.0"},
            })
        if method == "tools/list":
            return self._result(request_id, {"tools": TOOLS})
        if method == "tools/call":
            params = message.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "searchHotels":
                return self._result(request_id, search_result(args))
            if name == "getHotelDetail":
                return self._result(request_id, detail_result(args))
            if name == "getHotelSearchTags":
                return self._result(request_id, {
                    "content": [{"type": "text", "text": "星级、距离、偏好标签"}],
                    "structuredContent": {"tags": ["湖景", "亲子", "地铁", "早餐"]},
                    "isError": False,
                })
            return self._error(request_id, -32602, "unknown tool")
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
    print(f"Hotel MCP demo: http://127.0.0.1:{PORT}/mcp", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
