package app.jobpilot.claude;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import app.jobpilot.settings.AppSettings;
import app.jobpilot.settings.JobPilotProperties;

/**
 * How many model calls may run at the same time, for the whole app. It is the single throttle behind scoring, document
 * writing and everything else; the Settings page can change it while the app runs (SPEC 7.3).
 */
@Component
public class ClaudeConcurrency {

    public static final int MIN = 1;
    public static final int MAX = 8;
    static final String KEY = "claude.concurrency";

    private static final class Permits extends Semaphore {
        Permits(int n) {
            super(n, true);
        }

        void reduce(int n) {
            reducePermits(n);
        }
    }

    private final AppSettings settings;
    private final Permits permits;
    private int limit;

    @Autowired
    public ClaudeConcurrency(JobPilotProperties props, AppSettings settings) {
        this(clamp(settings.getInt(KEY).orElse(props.claude().concurrency())), settings);
    }

    private ClaudeConcurrency(int limit, AppSettings settings) {
        this.settings = settings;
        this.limit = limit;
        this.permits = new Permits(limit);
    }

    /** A fixed limit that is not stored anywhere (tests and tools). */
    public static ClaudeConcurrency fixed(int limit) {
        return new ClaudeConcurrency(clamp(limit), null);
    }

    public void acquire() throws InterruptedException {
        permits.acquire();
    }

    public void release() {
        permits.release();
    }

    public synchronized int limit() {
        return limit;
    }

    /** Applies at once: running calls finish, and fewer or more new ones start. Returns the value actually set. */
    public synchronized int setLimit(int wanted) {
        int n = clamp(wanted);
        if (n > limit) {
            permits.release(n - limit);
        } else if (n < limit) {
            permits.reduce(limit - n);
        }
        limit = n;
        if (settings != null) {
            settings.put(KEY, n);
        }
        return n;
    }

    private static int clamp(int n) {
        return Math.max(MIN, Math.min(MAX, n));
    }
}
