package se.caibirch.jobpilot.apply;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Owns the one persistent Chromium (SPEC 9.1). Playwright for Java is not thread-safe, so every call on
 * Playwright, the context and its pages runs on a single dedicated thread. The browser starts on first use, never at
 * application start, and only ever uses the profile under the data directory: never the user's own browser profile.
 */
@Service
public class BrowserService implements DisposableBean {

    public static class TabClosedException extends RuntimeException {
        public TabClosedException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BrowserService.class);
    private static final int CALL_TIMEOUT_SECONDS = 180;
    private static final int CLOSE_TIMEOUT_SECONDS = 20;
    private static final int NAVIGATION_TIMEOUT_MS = 30_000;

    private final JobPilotProperties.Browser config;
    private final java.nio.file.Path profileDir;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jobpilot-browser");
        t.setDaemon(true);
        return t;
    });

    // Only touched on `thread`.
    private Playwright playwright;
    private BrowserContext context;
    private final Map<Long, Page> tabs = new HashMap<>();

    public BrowserService(JobPilotProperties props) {
        this.config = props.browser();
        this.profileDir = props.browserProfileDir();
    }

    /** Runs the task on the browser thread and waits for it. */
    public <T> T call(Callable<T> task) {
        return call(task, CALL_TIMEOUT_SECONDS);
    }

    private <T> T call(Callable<T> task, int timeoutSeconds) {
        try {
            return thread.submit(task).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The browser did not answer in " + timeoutSeconds + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the browser");
        }
    }

    /** Opens (or reuses) the tab of a job and loads the URL. At most {@code maxTabs} tabs are open. */
    public void openTab(long jobId, String url) {
        call(() -> {
            BrowserContext ctx = ensureContext();
            Page existing = tabs.get(jobId);
            if (existing != null && existing.isClosed()) {
                tabs.remove(jobId);
                existing = null;
            }
            if (existing == null) {
                tabs.values().removeIf(Page::isClosed);
                // The aktivitetsrapport tab (negative key) is not an application tab and does not count against the cap.
                if (jobId >= 0 && tabs.keySet().stream().filter(k -> k >= 0).count() >= config.maxTabs()) {
                    throw new IllegalArgumentException("At most " + config.maxTabs() + " application tabs can be open; close one first");
                }
                existing = ctx.newPage();
                tabs.put(jobId, existing);
            }
            existing.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            return null;
        });
    }

    /** Runs {@code fn} with the job's page on the browser thread; throws {@link TabClosedException} if the tab is gone. */
    public <T> T withTab(long jobId, Function<Page, T> fn) {
        return call(() -> {
            Page page = tabs.get(jobId);
            if (page == null || page.isClosed()) {
                tabs.remove(jobId);
                throw new TabClosedException("The tab for job " + jobId + " was closed");
            }
            try {
                return fn.apply(page);
            } catch (PlaywrightException e) {
                if (page.isClosed() || String.valueOf(e.getMessage()).contains("closed")) {
                    tabs.remove(jobId);
                    throw new TabClosedException("The tab for job " + jobId + " was closed");
                }
                throw e;
            }
        });
    }

    public boolean hasTab(long jobId) {
        return call(() -> {
            Page p = tabs.get(jobId);
            return p != null && !p.isClosed();
        });
    }

    public Optional<Integer> openTabCount() {
        return playwright == null ? Optional.empty() : Optional.of(call(() -> {
            tabs.values().removeIf(Page::isClosed);
            return tabs.size();
        }));
    }

    /** Closing must never wait minutes behind a busy or stuck browser thread, so it gives up after a short time. */
    public void closeTab(long jobId) {
        call(() -> {
            Page p = tabs.remove(jobId);
            if (p != null && !p.isClosed()) {
                p.close();
            }
            return null;
        }, CLOSE_TIMEOUT_SECONDS);
    }

    /** The profile is created on the first call, with a Playwright download if needed. Recreated if the user closed the browser. */
    private BrowserContext ensureContext() {
        if (context != null) {
            try {
                context.pages();
                return context;
            } catch (PlaywrightException e) {
                log.info("The browser was closed; starting a new one");
                context = null;
                tabs.clear();
            }
        }
        try {
            if (playwright == null) {
                playwright = Playwright.create();
            }
            Files.createDirectories(profileDir);
            context = playwright.chromium().launchPersistentContext(profileDir,
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(config.headless()).setViewportSize(null));
            return context;
        } catch (java.io.IOException | PlaywrightException e) {
            throw new IllegalStateException("Cannot start the browser: " + e.getMessage()
                    + ". Install Chromium once with: cd backend && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
                    + "-Dexec.args=\"install chromium\"", e);
        }
    }

    @Override
    public void destroy() {
        try {
            thread.submit(() -> {
                try {
                    if (context != null) {
                        context.close();
                    }
                    if (playwright != null) {
                        playwright.close();
                    }
                } catch (RuntimeException e) {
                    log.debug("Browser shutdown: {}", e.toString());
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Browser shutdown: {}", e.toString());
        }
        thread.shutdownNow();
    }

    /** Brings a job's tab to the front (SPEC 12: "Focus tab"). */
    public void bringToFront(long jobId) {
        withTab(jobId, page -> {
            page.bringToFront();
            return null;
        });
    }
}
