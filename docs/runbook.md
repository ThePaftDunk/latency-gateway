# Diagnose latency and upstream failure

1. Inspect HTTP status counts and client percentiles together. A fast 503 does not improve useful latency.
2. Compare cache hit rate, upstream calls, single-flight waiters, timeouts, and bulkhead rejections. A hot cache benchmark does not measure upstream capacity.
3. If the circuit is open (`gateway_circuit_state=1`), restore the upstream and wait for its cooldown. One successful half-open probe closes it. Repeated restarts erase useful state and create fresh upstream traffic.
4. If the request bulkhead rejects, check real arrival rate and upstream latency before increasing `MAX_IN_FLIGHT`. A higher limit can move the queue into a database or remote API.
5. Check cache TTL against freshness expectations. Compare equivalent cached/uncached workloads with explicit warmup and longer runs before making an SLO claim.
6. During deployment, readiness drops before HTTP draining; keep the termination grace period longer than the drain interval. Verify rolling shutdown under your ingress behavior.
7. The real HTTP upstream has a 65,536-byte response-body limit. Oversized, truncated and non-200 bodies become uncached upstream failures and contribute to the circuit breaker. Check upstream response sizes and transport errors when 503s rise without deadline timeouts. The public error deliberately omits upstream payloads; it does not identify each failure subtype.

Synthetic failure commands:

```sh
curl -i 'http://localhost:8080/quotes?symbol=SLOW'
curl -i 'http://localhost:8080/quotes?symbol=FAIL0'
curl http://localhost:8080/metrics
```

Before production: authenticated ingress, HTTP connection/body bounds, upstream response validation, distributed capacity planning, load tests including outages, and a freshness contract reviewed with API consumers.
