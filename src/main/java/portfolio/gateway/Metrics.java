package portfolio.gateway;

import java.util.concurrent.atomic.LongAdder;

/** Fixed buckets: bounded memory and label cardinality; Prometheus histogram format. */
public final class Metrics {
    private final double[] boundaries = {.001, .005, .01, .025, .05, .1, .25, .5, 1};
    private final LongAdder[] buckets = new LongAdder[boundaries.length];
    private final LongAdder count = new LongAdder(), nanos = new LongAdder();
    public final LongAdder ok = new LongAdder(), unavailable = new LongAdder(), rejected = new LongAdder();
    public Metrics() { for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder(); }
    public void observe(long durationNanos) {
        count.increment(); nanos.add(durationNanos);
        for (int i = 0; i < boundaries.length; i++) if (durationNanos / 1e9 <= boundaries[i]) buckets[i].increment();
    }
    public String render(QuoteService service) {
        StringBuilder s = new StringBuilder("# TYPE gateway_request_duration_seconds histogram\n");
        for (int i = 0; i < boundaries.length; i++) s.append("gateway_request_duration_seconds_bucket{le=\"").append(boundaries[i]).append("\"} ").append(buckets[i].sum()).append('\n');
        s.append("gateway_request_duration_seconds_bucket{le=\"+Inf\"} ").append(count.sum()).append('\n');
        s.append("gateway_request_duration_seconds_count ").append(count.sum()).append('\n');
        s.append("gateway_request_duration_seconds_sum ").append(nanos.sum() / 1e9).append('\n');
        s.append("gateway_requests_total{status=\"200\"} ").append(ok.sum()).append('\n');
        s.append("gateway_requests_total{status=\"503\"} ").append(unavailable.sum()).append('\n');
        s.append("gateway_bulkhead_rejections_total ").append(rejected.sum()).append('\n');
        s.append("gateway_cache_hits_total ").append(service.hits.sum()).append('\n');
        s.append("gateway_cache_misses_total ").append(service.misses.sum()).append('\n');
        s.append("gateway_single_flight_waiters_total ").append(service.shared.sum()).append('\n');
        s.append("gateway_upstream_calls_total ").append(service.upstreamCalls.sum()).append('\n');
        s.append("gateway_timeouts_total ").append(service.timeouts.sum()).append('\n');
        s.append("gateway_cache_entries ").append(service.cacheSize()).append('\n');
        s.append("gateway_circuit_state ").append(service.circuitState().ordinal()).append('\n');
        return s.toString();
    }
}
