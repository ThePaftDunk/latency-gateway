# Local benchmark evidence

Recorded 8 October 2026 on macOS 26.1, Apple silicon, OpenJDK 24.0.2. Source targets Java 21. Python 3.9.6 generated the client workload using 32 threads and a new HTTP request for each sample.

Both configurations used **10,000 requests, 32 concurrent clients, 20 symbols, a 30 ms synthetic upstream delay, a 120 ms deadline, and a 64-request bulkhead**. Single-flight coalescing remains enabled in both. The only server configuration difference is cache TTL: 2,000 ms versus 0.

| Observed metric | Cache TTL 2,000 ms | Cache TTL 0 |
|---|---:|---:|
| Requests | 10,000 | 10,000 |
| Successful responses | 10,000 | 10,000 |
| Error responses | 0 | 0 |
| Elapsed seconds | 2.172 | 12.649 |
| Client requests/second | 4,603.08 | 790.59 |
| p50, ms | 6.186 | 40.049 |
| p95, ms | 11.575 | 47.580 |
| p99, ms | 16.118 | 51.364 |
| Cache hits | 9,936 | 0 |
| Loader misses | 40 | 6,209 |
| Single-flight waiters | 24 | 3,791 |

Raw reports: [cached.json](reports/cached.json) and [uncached.json](reports/uncached.json). Each report includes workload parameters, runtime/host information, status counts, cache outcomes, and percentiles for all requests and successful requests separately.

## Reproduce

Terminal 1:

```sh
./scripts/run.sh
```

Terminal 2:

```sh
PORT=8082 CACHE_TTL_MS=0 ./scripts/run.sh
```

Terminal 3:

```sh
python3 scripts/benchmark.py --requests 10000 --concurrency 32 --symbols 20 --output build/cached.json
python3 scripts/benchmark.py --url http://localhost:8082 --requests 10000 --concurrency 32 --symbols 20 --output build/uncached.json
```

Nearest-rank percentiles use client elapsed time, including connection setup, dispatch, network, server work, response read, and Python overhead. The throughput denominator is total run duration. Cache outcomes come from response headers, not inferred from latency.

## Interpretation limits

This is one sequential run of each configuration, not a statistically controlled capacity experiment. The cached JVM had served prior smoke/benchmark traffic and had a different warmup history from the second process. The cache had expired before this measured run. The synthetic hot-key workload strongly favors caching; a large unique-key workload or different freshness requirement changes the result.

Both client and server ran on the same machine. Python scheduling and HTTP connection overhead influence the measurement. The short cached run spans roughly one TTL rollover, and no GC, CPU or allocation profile was collected. There is no real market feed, WAN latency, authenticated ingress, or downstream database. These figures demonstrate the implementation and measurement method; they do not establish an SLO or production peak throughput.

For stronger evidence, add fixed warmup, several randomized repeated runs, longer steady-state phases, cold/unique-key workloads, overload and upstream-outage phases, JFR allocation/GC profiling, and an independent load generator.
