package app.jobpilot.search;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/** Exponential backoff on 429, 5xx and network errors (SPEC 6.1). Other 4xx fail immediately. */
final class HttpRetry {

    private static final Logger log = LoggerFactory.getLogger(HttpRetry.class);
    private static final int ATTEMPTS = 5;

    private HttpRetry() {
    }

    static <T> T call(String what, Duration baseBackoff, Supplier<T> request) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return request.get();
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && status < 500) {
                    throw e;
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }
            if (attempt < ATTEMPTS - 1) {
                long wait = baseBackoff.toMillis() << attempt;
                log.warn("{} failed ({}), retrying in {} ms", what, last.getMessage(), wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying " + what, e);
                }
            }
        }
        throw new IllegalStateException(what + " failed after " + ATTEMPTS + " attempts: " + last.getMessage(), last);
    }
}
