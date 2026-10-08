#!/usr/bin/env python3
"""Real HTTP boundary test: methods, cache headers, errors, probes and metrics."""
import concurrent.futures, json, os, socket, subprocess, threading, time, urllib.request, urllib.error
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
process = subprocess.Popen(["java", "-cp", "build/test-classes", "portfolio.gateway.Main"], env={**os.environ, "PORT": str(port), "MAX_IN_FLIGHT": "2"}, stdout=subprocess.DEVNULL)
def get(path, method="GET"):
    try:
        response = urllib.request.urlopen(urllib.request.Request(f"http://localhost:{port}{path}", method=method), timeout=3)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()
try:
    deadline = time.monotonic() + 10
    while True:
        try:
            assert get("/health/ready")[0] == 200; break
        except (OSError, AssertionError):
            if time.monotonic() > deadline: raise
            time.sleep(.05)
    code, headers, body = get("/quotes?symbol=DEMO")
    assert code == 200 and headers["X-Cache"] == "MISS" and json.loads(body)["synthetic"]
    assert get("/quotes?symbol=DEMO")[1]["X-Cache"] == "HIT"
    assert get("/quotes?symbol=bad")[0] == 400
    assert get("/quotes?symbol=%ZZ")[0] == 400
    assert get("/quotes?symbol=A&symbol=B")[0] == 400
    assert get("/quotes?symbol=A", "POST")[0] == 405
    assert get("/quotes-extra?symbol=A")[0] == 404
    assert get("/quotes?symbol=SLOW")[0] == 503
    for index in range(5): assert get(f"/quotes?symbol=FAIL{index}")[0] == 503
    assert "circuit open" in get("/quotes?symbol=NEW")[2]
    assert get("/quotes?symbol=DEMO")[0] == 200  # Cache still serves during outage.
    text = get("/metrics")[2]
    assert "gateway_timeouts_total 1" in text and "gateway_circuit_state 1" in text
    time.sleep(2.1)
    assert get("/quotes?symbol=RECOVERED")[0] == 200
    barrier = threading.Barrier(12)
    def stress(index):
        barrier.wait(timeout=3)
        return get(f"/quotes?symbol=STRESS{index}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        responses = list(pool.map(stress, range(12)))
    assert any(r[0] == 200 for r in responses)
    assert any(r[0] == 503 and "capacity unavailable" in r[2] for r in responses)
    assert get("/health/live")[0] == 200
    print("PASS: HTTP smoke (validation, cache, deadline, circuit recovery, bulkhead, metrics)")
finally:
    process.terminate()
    try: process.wait(timeout=8)
    except subprocess.TimeoutExpired: process.kill(); process.wait()
