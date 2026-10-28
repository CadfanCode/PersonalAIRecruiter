package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ClaudeConcurrencyTest {

    @Test
    void theLimitIsClampedToItsRange() {
        assertThat(ClaudeConcurrency.fixed(0).limit()).isEqualTo(ClaudeConcurrency.MIN);
        assertThat(ClaudeConcurrency.fixed(99).limit()).isEqualTo(ClaudeConcurrency.MAX);
    }

    @Test
    void raisingTheLimitLetsMoreCallsStartAtOnce() throws Exception {
        ClaudeConcurrency c = ClaudeConcurrency.fixed(1);
        c.acquire();
        AtomicInteger started = new AtomicInteger();
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                c.acquire();
                started.incrementAndGet();
            } catch (InterruptedException ignored) {
            }
        });
        Thread.sleep(100);
        assertThat(started.get()).isZero();
        c.setLimit(2);
        waiting.join(2000);
        assertThat(started.get()).isEqualTo(1);
    }

    @Test
    void loweringTheLimitHoldsBackNewCallsUntilEnoughFinish() throws Exception {
        ClaudeConcurrency c = ClaudeConcurrency.fixed(3);
        c.acquire();
        c.acquire();
        c.acquire();
        c.setLimit(1);
        AtomicInteger started = new AtomicInteger();
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                c.acquire();
                started.incrementAndGet();
            } catch (InterruptedException ignored) {
            }
        });
        c.release();
        c.release();
        Thread.sleep(150);
        assertThat(started.get()).isZero();
        c.release();
        waiting.join(2000);
        assertThat(started.get()).isEqualTo(1);
    }
}
