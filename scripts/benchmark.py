#!/usr/bin/env python3
"""Client-side elapsed latency, including errors; stdlib-only and reproducible."""
import argparse, concurrent.futures, json, math, platform, time, urllib.error, urllib.request
parser = argparse.ArgumentParser()
parser.add_argument("--url", default="http://localhost:8080")
parser.add_argument("--requests", type=int, default=2000)
parser.add_argument("--concurrency", type=int, default=32)
parser.add_argument("--symbols", type=int, default=20)
parser.add_argument("--output", default="build/benchmark.json")
args = parser.parse_args()
if min(args.requests, args.concurrency, args.symbols) < 1: parser.error("counts must be positive")
def request(index):
    start = time.perf_counter_ns()
    try:
        with urllib.request.urlopen(f"{args.url}/quotes?symbol=SYM{index % args.symbols}", timeout=5) as response:
            response.read(); status = response.status; source = response.headers.get("X-Cache", "none")
    except urllib.error.HTTPError as error:
        error.read(); status = error.code; source = "none"
    except OSError:
        status = "transport_error"; source = "none"
    return (time.perf_counter_ns() - start) / 1e6, status, source
start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
    results = list(pool.map(request, range(args.requests)))
elapsed = time.perf_counter() - start
def percentiles(values):
    values = sorted(values)
    return {f"p{p}_ms": round(values[min(len(values)-1, math.ceil(len(values)*p/100)-1)], 3) if values else None for p in (50, 95, 99)}
statuses, cache = {}, {}
for duration, status, source in results:
    statuses[str(status)] = statuses.get(str(status), 0) + 1; cache[source] = cache.get(source, 0) + 1
report = {"workload": vars(args), "host": {"platform": platform.platform(), "python": platform.python_version()}, "elapsed_seconds": round(elapsed, 3), "requests_per_second": round(args.requests/elapsed, 2), "all_requests": percentiles([r[0] for r in results]), "successful_requests": percentiles([r[0] for r in results if r[1] == 200]), "statuses": statuses, "cache": cache, "note": "Synthetic local quote lookup; client overhead included; not a production capacity claim."}
print(json.dumps(report, indent=2))
with open(args.output, "w") as file: json.dump(report, file, indent=2)
if statuses.get("200", 0) == 0: raise SystemExit("No successful requests; benchmark failed")
