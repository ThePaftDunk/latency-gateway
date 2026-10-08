package portfolio.gateway;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One recovery probe at a time. Success/failure must be reported for every permit. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }
    public record Permit(long generation) {}
    private final int threshold;
    private final long cooldownNanos;
    private final LongSupplier clock;
    private int failures;
    private long opensUntil;
    private State state = State.CLOSED;
    private boolean probing;
    private long generation;
    public CircuitBreaker(int threshold, Duration cooldown, LongSupplier clock) {
        if (threshold < 1 || cooldown.isNegative() || cooldown.isZero()) throw new IllegalArgumentException();
        this.threshold = threshold; this.cooldownNanos = cooldown.toNanos(); this.clock = clock;
    }
    public synchronized Permit permit() {
        if (state == State.OPEN) {
            if (clock.getAsLong() < opensUntil) return null;
            state = State.HALF_OPEN;
        }
        if (state == State.HALF_OPEN) {
            if (probing) return null;
            probing = true;
        }
        return new Permit(generation);
    }
    public synchronized void success(Permit permit) {
        // Calls admitted before the circuit opened cannot close an open circuit.
        if (permit.generation() != generation || state == State.OPEN) return;
        if (state == State.HALF_OPEN) generation++;
        failures = 0; probing = false; state = State.CLOSED;
    }
    public synchronized void failure(Permit permit) {
        if (permit.generation() != generation || state == State.OPEN) return;
        if (state == State.HALF_OPEN || ++failures >= threshold) {
            state = State.OPEN; opensUntil = clock.getAsLong() + cooldownNanos; probing = false; generation++;
        }
    }
    public synchronized State state() { return state; }
}
