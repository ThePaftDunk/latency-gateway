package portfolio.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

public final class QuoteService implements AutoCloseable {
    public interface Backend { String fetch(String symbol) throws Exception; }
    public record Result(String json, String source) {}
    private record Entry(String json, long expiresAt) {}
    public static final class Unavailable extends RuntimeException { public Unavailable(String message) { super(message); } }
    private final Backend backend;
    private final CircuitBreaker breaker;
    private final Duration deadline;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final int capacity;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final LongAdder hits = new LongAdder(), misses = new LongAdder(), shared = new LongAdder(), timeouts = new LongAdder(), upstreamCalls = new LongAdder();
    public QuoteService(Backend backend, CircuitBreaker breaker, Duration deadline, Duration ttl, int capacity, LongSupplier clock) {
        if (capacity < 1 || deadline.isNegative() || deadline.isZero() || ttl.isNegative()) throw new IllegalArgumentException();
        this.backend = backend; this.breaker = breaker; this.deadline = deadline; this.ttlNanos = ttl.toNanos(); this.capacity = capacity; this.clock = clock;
    }
    public Result resolve(String symbol) {
        synchronized (cache) {
            Entry value = cache.get(symbol);
            if (value != null && value.expiresAt() > clock.getAsLong()) { hits.increment(); return new Result(value.json(), "HIT"); }
            if (value != null) cache.remove(symbol);
        }
        CompletableFuture<String> candidate = new CompletableFuture<>();
        CompletableFuture<String> existing = inFlight.putIfAbsent(symbol, candidate);
        if (existing != null) {
            shared.increment();
            try { return new Result(existing.get(deadline.toMillis() + 50, TimeUnit.MILLISECONDS), "SINGLE_FLIGHT"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable("interrupted"); }
            catch (ExecutionException | TimeoutException e) { throw new Unavailable("upstream unavailable"); }
        }
        Future<String> work = null;
        CircuitBreaker.Permit permit = null;
        try {
            // Recheck after becoming leader: another leader may have just filled the cache.
            synchronized (cache) {
                Entry value = cache.get(symbol);
                if (value != null && value.expiresAt() > clock.getAsLong()) {
                    hits.increment(); candidate.complete(value.json()); return new Result(value.json(), "HIT");
                }
            }
            permit = breaker.permit();
            if (permit == null) throw new Unavailable("circuit open");
            misses.increment();
            work = executor.submit(() -> { upstreamCalls.increment(); return backend.fetch(symbol); });
            String json = work.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            breaker.success(permit);
            synchronized (cache) {
                cache.put(symbol, new Entry(json, clock.getAsLong() + ttlNanos));
                while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            }
            candidate.complete(json);
            return new Result(json, "MISS");
        } catch (TimeoutException e) {
            timeouts.increment(); breaker.failure(permit); candidate.completeExceptionally(e); throw new Unavailable("upstream deadline exceeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); if (permit != null) breaker.failure(permit); candidate.completeExceptionally(e); throw new Unavailable("interrupted");
        } catch (ExecutionException e) {
            breaker.failure(permit); candidate.completeExceptionally(e.getCause()); throw new Unavailable("upstream unavailable");
        } catch (RuntimeException e) {
            if (permit != null) breaker.failure(permit);
            candidate.completeExceptionally(e); throw e;
        } finally {
            if (work != null && !work.isDone()) work.cancel(true);
            inFlight.remove(symbol, candidate);
        }
    }
    public int cacheSize() { synchronized (cache) { return cache.size(); } }
    public CircuitBreaker.State circuitState() { return breaker.state(); }
    public void close() { executor.shutdownNow(); }
}
