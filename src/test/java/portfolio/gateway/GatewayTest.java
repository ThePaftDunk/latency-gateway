package portfolio.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class GatewayTest {
    public static void main(String[] args) throws Exception {
        breakerRecovery(); cacheExpiryAndCapacity(); singleFlight(); timeoutCancellation();
        System.out.println("PASS: 4 Java invariants (breaker generations, cache bounds/TTL, coalescing, cancellation)");
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static CircuitBreaker breaker() { return new CircuitBreaker(5, Duration.ofSeconds(1), System::nanoTime); }
    static void breakerRecovery() {
        AtomicLong now = new AtomicLong();
        var b = new CircuitBreaker(2, Duration.ofNanos(10), now::get);
        var stale = b.permit(); var one = b.permit(); var two = b.permit();
        b.failure(one); b.failure(two); check(b.state() == CircuitBreaker.State.OPEN, "must open");
        b.success(stale); check(b.state() == CircuitBreaker.State.OPEN, "stale success must not close");
        check(b.permit() == null, "must reject while cooling"); now.set(11);
        var probe = b.permit(); check(probe != null && b.permit() == null, "one half-open probe");
        b.failure(stale); check(b.state() == CircuitBreaker.State.HALF_OPEN, "old failure must not poison probe");
        b.success(probe); check(b.state() == CircuitBreaker.State.CLOSED, "probe must recover");
    }
    static void cacheExpiryAndCapacity() {
        AtomicLong now = new AtomicLong();
        try (var service = new QuoteService(s -> s, breaker(), Duration.ofSeconds(1), Duration.ofNanos(10), 2, now::get)) {
            check(service.resolve("A").source().equals("MISS"), "initial miss");
            check(service.resolve("A").source().equals("HIT"), "cache hit");
            now.set(11); check(service.resolve("A").source().equals("MISS"), "expiry");
            service.resolve("B"); service.resolve("C"); check(service.cacheSize() == 2, "bounded cache");
            check(service.resolve("A").source().equals("MISS"), "LRU eviction");
        }
    }
    static void singleFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var service = new QuoteService(s -> { entered.countDown(); if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException(); return s; }, breaker(), Duration.ofSeconds(3), Duration.ofSeconds(1), 16, System::nanoTime);
             var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<QuoteService.Result>>();
            results.add(clients.submit(() -> service.resolve("A")));
            check(entered.await(1, TimeUnit.SECONDS), "leader entered");
            for (int i = 0; i < 9; i++) results.add(clients.submit(() -> service.resolve("A")));
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (service.shared.sum() < 9 && System.nanoTime() < stop) Thread.yield();
            release.countDown();
            for (var result : results) check(result.get(2, TimeUnit.SECONDS).json().equals("A"), "same result");
            check(service.upstreamCalls.sum() == 1, "one upstream call");
            check(service.shared.sum() == 9, "all concurrent waiters coalesced");
        } finally { release.countDown(); }
    }
    static void timeoutCancellation() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var service = new QuoteService(s -> { try { new CountDownLatch(1).await(); return s; } catch (InterruptedException e) { cancelled.countDown(); throw e; } }, breaker(), Duration.ofMillis(30), Duration.ZERO, 2, System::nanoTime)) {
            try { service.resolve("A"); throw new AssertionError("must timeout"); } catch (QuoteService.Unavailable expected) {}
            check(cancelled.await(1, TimeUnit.SECONDS), "cancel backend work");
            check(service.cacheSize() == 0 && service.timeouts.sum() == 1, "no timeout cached");
        }
    }
}
