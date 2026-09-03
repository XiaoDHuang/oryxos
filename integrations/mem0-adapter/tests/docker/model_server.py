"""隔离HTTPS模型对端，仅用于真实SDK/PG/HTTP组合测试，不冒充真实模型验收。"""

import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
from threading import Lock

STATE = {"responses": [], "calls": []}
LOCK = Lock()
CONTROL = Path("/run/secrets/model_control").read_text().strip()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        content = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 1024 * 1024:
                return self.reply(413, {})
            request = json.loads(self.rfile.read(length))
            with LOCK:
                if self.path == "/fixture":
                    if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + CONTROL):
                        return self.reply(403, {})
                    if "responses" in request:
                        STATE["responses"] = request["responses"]
                        STATE["calls"] = []
                    return self.reply(200, {"calls": STATE["calls"], "remaining": len(STATE["responses"])})
                STATE["calls"].append({"path": self.path, "model": request.get("model")})
                if self.path == "/v1/embeddings":
                    return self.reply(200, {"model": request["model"],
                        "data": [{"index": 0, "embedding": [1.0, 0.0]}],
                        "usage": {"prompt_tokens": 2, "total_tokens": 2}})
                if self.path == "/v1/chat/completions" and STATE["responses"]:
                    response = STATE["responses"].pop(0)
                    content = response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)
                    return self.reply(200, {"model": request["model"], "choices": [{"index": 0,
                        "finish_reason": "stop", "message": {"role": "assistant", "content": content}}],
                        "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}})
                return self.reply(503, {"error": "合成模型响应未配置"})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 9443), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain("/certs/server.pem", "/certs/server-key.pem")
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
