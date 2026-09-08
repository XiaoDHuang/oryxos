#!/usr/bin/env python3
# 回环 webhook 接收端:打印每个 POST 的正文,用于肉眼核对推送内容是否带实时气温。
# 用法: python my-agent/webhook-receiver.py [端口=18099]
import http.server
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18099


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        transfer = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in transfer:
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    break
                size = int(size_line, 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            body = b"".join(chunks).decode("utf-8", errors="replace")
        else:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length).decode("utf-8", errors="replace")
        print("WEBHOOK_RECV %s %s: %s" % (self.path, self.headers.get("Content-Type"), body), flush=True)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


print("webhook receiver listening on 127.0.0.1:%d" % PORT, flush=True)
http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
