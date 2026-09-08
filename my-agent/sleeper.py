#!/usr/bin/env python3
# 慢对端:每个请求先睡 90 秒再回应,用于真实 60 秒超时链路验证。
import http.server
import time


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(90)
        body = b'{"slow": true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


print("sleeper listening on 127.0.0.1:18098 (90s per request)", flush=True)
http.server.ThreadingHTTPServer(("127.0.0.1", 18098), Handler).serve_forever()
