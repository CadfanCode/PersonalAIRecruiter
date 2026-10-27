package se.caibirch.jobpilot.pipeline;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
public class PipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);

    /** Two runs at a time and a short queue; more is rejected rather than piling up (SPEC 3). */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService pipelineExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "pipeline-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10), factory);
    }

    /**
     * Form filling has its own small pool: a long documents batch or a discovery run must not leave "Fill all" sitting in
     * OPENING behind it. The browser itself is single-threaded anyway (BrowserService), so two threads is plenty.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService applyExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "apply-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), factory);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns(ApplicationReadyEvent event) {
        RunRepository runs = event.getApplicationContext().getBean(RunRepository.class);
        int n = runs.failInterrupted(event.getApplicationContext().getBean(Clock.class).instant());
        if (n > 0) {
            log.warn("Marked {} interrupted run(s) as FAILED", n);
        }
    }
}
