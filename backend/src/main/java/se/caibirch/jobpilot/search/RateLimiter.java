package se.caibirch.jobpilot.search;

/** Spaces calls at least 1/requestsPerSecond apart (SPEC 6.1: at most 5 requests per second). */
public class RateLimiter {

    private final long minIntervalNanos;
    private long nextFree = System.nanoTime();

    public RateLimiter(int requestsPerSecond) {
        this.minIntervalNanos = 1_000_000_000L / Math.max(1, requestsPerSecond);
    }

    public synchronized void acquire() {
        long now = System.nanoTime();
        long wait = nextFree - now;
        nextFree = Math.max(now, nextFree) + minIntervalNanos;
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
