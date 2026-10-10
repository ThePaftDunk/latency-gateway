#!/usr/bin/env python3
"""Exercise the real JDK HTTP adapter against bounded and interrupted responses."""
import collections
import http.server
import json
import os
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

LIMIT = 65536
calls = collections.Counter()
calls_lock = threading.Lock()
release_tail = threading.Event()


def sized_json(size):
    prefix, suffix = b'{"value":"', b'"}'
    return prefix + b'x' * (size - len(prefix) - len(suffix)) + suffix


class Upstream(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            pass  # The limiter can cancel while the server waits for another request.

    def do_GET(self):
        symbol = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)["symbol"][0]
        with calls_lock:
            calls[symbol] += 1
        body = b'{"value":"ok"}'
        if symbol in ("EXACT", "CHUNKEXACT"):
            body = sized_json(LIMIT)
        elif symbol in ("OVERSIZED", "CHUNKOVER"):
            body = sized_json(LIMIT + 1)
        elif symbol == "UNICODE":
            body = json.dumps({"value": "€" * 22000}, ensure_ascii=False).encode("utf-8")
            assert len(body) > LIMIT and len(body.decode("utf-8")) < LIMIT
        self.send_response(500 if symbol == "ERROR" else 200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if symbol.startswith("CHUNK") or symbol == "STALLED":
            self.send_header("Transfer-Encoding", "chunked")
        else:
            self.send_header("Content-Length", str(len(body) + (10 if symbol == "TRUNCATED" else 0)))
        self.end_headers()
        try:
            if symbol.startswith("CHUNK") or symbol == "STALLED":
                fragment = body[:1] if symbol == "STALLED" else body
                self.wfile.write(f"{len(fragment):x}\r\n".encode() + fragment + b"\r\n")
                self.wfile.flush()
                if symbol in ("CHUNKOVER", "STALLED"):
                    # The limiter must reject CHUNKOVER without waiting for the end marker.
                    release_tail.wait(10)
                self.wfile.write(b"0\r\n\r\n")
            else:
                self.wfile.write(body)
            self.wfile.flush()
            if symbol == "TRUNCATED":
                self.close_connection = True
        except (BrokenPipeError, ConnectionResetError):
            pass  # A rejected or interrupted response is allowed to close the connection.


upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
upstream_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
upstream_thread.start()
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
process = subprocess.Popen(
    ["java", "-cp", "build/test-classes", "portfolio.gateway.Main"],
    env={**os.environ, "PORT": str(port), "MAX_IN_FLIGHT": "2", "UPSTREAM_TIMEOUT_MS": "1500",
         "CACHE_TTL_MS": "10000", "CACHE_CAPACITY": "32",
         "UPSTREAM_URL": f"http://127.0.0.1:{upstream.server_port}/quotes"},
    stdout=subprocess.DEVNULL,
)


def get(path):
    try:
        response = urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode("utf-8")


try:
    deadline = time.monotonic() + 10
    while True:
        try:
            assert get("/health/ready")[0] == 200
            break
        except (OSError, AssertionError):
            if process.poll() is not None or time.monotonic() > deadline:
                raise
            time.sleep(.05)
    for symbol in ("EXACT", "CHUNKEXACT"):
        status, headers, body = get(f"/quotes?symbol={symbol}")
        assert status == 200 and headers["X-Cache"] == "MISS", symbol
        assert len(body.encode("utf-8")) == LIMIT and json.loads(body)["value"], symbol
        assert get(f"/quotes?symbol={symbol}")[1]["X-Cache"] == "HIT", symbol
        assert calls[symbol] == 1, symbol
    for index, symbol in enumerate(("OVERSIZED", "CHUNKOVER", "UNICODE", "TRUNCATED", "ERROR")):
        for _ in range(2):
            status, headers, body = get(f"/quotes?symbol={symbol}")
            assert status == 503 and json.loads(body)["error"] == "upstream unavailable", (symbol, body)
            assert "X-Cache" not in headers, symbol
        assert calls[symbol] == 2, f"{symbol} failure must not be cached"
        assert "gateway_timeouts_total 0" in get("/metrics")[2], f"{symbol} must fail before the deadline"
        # A fresh successful load resets the breaker between independent failure cases.
        assert get(f"/quotes?symbol=GOOD{index}")[1]["X-Cache"] == "MISS"
    assert not release_tail.is_set(), "oversized chunked response rejected before stream completion"
    assert get("/quotes?symbol=STALLED")[0] == 503, "deadline must cover the body stream"
    assert get("/quotes?symbol=AFTER_TIMEOUT")[1]["X-Cache"] == "MISS", "capacity must recover after interruption"
    assert get("/quotes?symbol=AFTER_TIMEOUT")[1]["X-Cache"] == "HIT"
    print("PASS: real HTTP upstream (exact fixed/chunked limit, streaming overflow, UTF-8 byte limit, truncation/status failures, no failed caching, stalled-body deadline)")
finally:
    release_tail.set()
    process.terminate()
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    upstream.shutdown()
    upstream.server_close()
    upstream_thread.join(timeout=2)
