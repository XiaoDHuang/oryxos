"""TLS终结转发代理：把隔离网内的HTTPS模型调用转发到同网络的Ollama HTTP后端。

仅转发 /v1/chat/completions 与 /v1/embeddings，其他路径一律 404。
不缓存、不记录正文、不修改语义；模型输出原样回传，便于审计归因于真实模型。
"""

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import ssl

# 默认指向同网络的 ollama 容器；真实模型模式由部署显式指定（如主机回环 host.docker.internal）。
UPSTREAM = tuple(os.environ.get("MODEL_UPSTREAM", "ollama:11434").rsplit(":", 1))
UPSTREAM = (UPSTREAM[0], int(UPSTREAM[1]))
ALLOWED_PATHS = frozenset({"/v1/chat/completions", "/v1/embeddings"})
MAX_REQUEST = 1024 * 1024
MAX_RESPONSE = 8 * 1024 * 1024
UPSTREAM_TIMEOUT = 600


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, status, body, content_type="application/json"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/healthz":
            return self.reply(200, "{}")
        self.reply(404, "{}")

    def do_PUT(self):
        self.reply(404, "{}")

    def do_DELETE(self):
        self.reply(404, "{}")

    def do_POST(self):
        if self.path not in ALLOWED_PATHS:
            return self.reply(404, "{}")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, "{}")
        if not 0 < length <= MAX_REQUEST:
            return self.reply(413, "{}")
        body = self.rfile.read(length)
        headers = {"Content-Type": self.headers.get("Content-Type", "application/json")}
        authorization = self.headers.get("Authorization")
        if authorization:
            headers["Authorization"] = authorization
        connection = http.client.HTTPConnection(*UPSTREAM, timeout=UPSTREAM_TIMEOUT)
        try:
            connection.request("POST", self.path, body, headers)
            response = connection.getresponse()
            payload = response.read(MAX_RESPONSE + 1)
            if len(payload) > MAX_RESPONSE:
                return self.reply(502, "{}")
            self.reply(response.status, payload, response.getheader("Content-Type") or "application/json")
        except (OSError, http.client.HTTPException):
            self.reply(502, "{}")
        finally:
            connection.close()


def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain("/certs/server.pem", "/certs/server-key.pem")
    server = ThreadingHTTPServer(("0.0.0.0", 9443), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
