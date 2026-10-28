package app.jobpilot.apply;

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

import app.jobpilot.settings.JobPilotProperties;

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
    private volatile boolean shown; // whether the window is on screen; only meaningful for a headed, background browser

    public BrowserService(JobPilotProperties props) {
        this.config = props.browser();
        this.profileDir = props.browserProfileDir();
    }

    /** Runs the task on the browser thread and waits for it. */
    public <T> T call(Callable<T> task) {
        return call(task, CALL_TIMEOUT_SECONDS);
    }

    /** True when the window is hidden off-screen until a person has to act. */
    public boolean backgroundMode() {
        return config.background() && !config.headless();
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
                Page foreground = shown ? ctx.pages().stream().filter(p -> !p.isClosed()).reduce((a, b) -> b).orElse(null) : null;
                existing = ctx.newPage();
                tabs.put(jobId, existing);
                if (foreground != null && foreground != existing) {
                    foreground.bringToFront(); // a new tab opens in front; the person reviewing another one keeps theirs
                }
            }
            if (backgroundMode() && !shown) {
                setWindowState(existing, "minimized"); // a new tab brings its window up: put it away again before anything loads
            }
            existing.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            return null;
        });
    }

    /** Runs {@code fn} with the job's page on the browser thread; throws {@link TabClosedException} if the tab is gone. */
    public <T> T withTab(long jobId, Function<Page, T> fn) {
        return withTab(jobId, fn, CALL_TIMEOUT_SECONDS);
    }

    /** Like {@link #withTab(long, Function)} with a shorter limit for steps that have their own deadline. */
    public <T> T withTab(long jobId, Function<Page, T> fn, int timeoutSeconds) {
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
        }, timeoutSeconds);
    }

    /**
     * Makes {@code page} (a tab the application's own click opened) the job's tab and closes the one it replaces. Must be called from
     * inside {@link #withTab}, which runs on the browser thread that owns the tab map.
     */
    public void adoptTab(long jobId, Page page) {
        Page old = tabs.put(jobId, page);
        if (old != null && old != page && !old.isClosed()) {
            old.close();
        }
        if (backgroundMode() && !shown) {
            setWindowState(page, "minimized"); // a popup is a window of its own: keep it out of sight too
        } else {
            page.bringToFront();
        }
    }

    /** Types the saved login into the tab's login form (see {@link BrowserGuard#fillSavedLogin}); never presses anything. */
    public BrowserGuard.LoginResult fillSavedLogin(long jobId, String host, String username, String password) {
        return withTab(jobId, page -> new BrowserGuard(page).fillSavedLogin(host, username, password));
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
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(config.headless()).setViewportSize(null);
            if (backgroundMode()) {
                options.setArgs(backgroundArgs());
                shown = false;
            }
            context = playwright.chromium().launchPersistentContext(profileDir, options);
            if (backgroundMode()) {
                context.pages().stream().findFirst().ifPresent(p -> setWindowState(p, "minimized")); // the flag is not honoured everywhere
            }
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

    /**
     * Chromium flags for the hidden window: started minimised, and no throttling of pages that are hidden or behind another tab,
     * which would make fills stall. The window is minimised rather than moved off-screen because window managers (Wayland above all)
     * keep a window on screen. On a Wayland session Chromium is started through XWayland (when there is an X display), which is what
     * lets the DevTools window calls below work.
     */
    static java.util.List<String> backgroundArgs() {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--start-minimized", "--window-size=1280,900",
                "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling"));
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (linux && "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")) && System.getenv("DISPLAY") != null) {
            args.add("--ozone-platform=x11");
        }
        return args;
    }

    /** True when the window is on screen. */
    public boolean windowShown() {
        return !backgroundMode() || shown;
    }

    /**
     * Brings the window up and the job's tab to the front: for a sign-in, a form ready for review, or a person taking over. A no-op
     * on the window when the browser is headless or not run in the background. Never fails the caller: a window that cannot be
     * restored is simply already where it is.
     */
    public void reveal(long jobId) {
        if (!backgroundMode()) {
            withTab(jobId, page -> {
                page.bringToFront();
                return null;
            });
            return;
        }
        withTab(jobId, page -> {
            setWindowState(page, "normal");
            page.bringToFront();
            shown = true;
            return null;
        }, 30);
    }

    /** Minimises the window again when nobody has to act on any tab. */
    public void hide() {
        if (!backgroundMode() || !shown || context == null) {
            return;
        }
        try {
            call(() -> {
                context.pages().stream().filter(p -> !p.isClosed()).findFirst().ifPresent(p -> setWindowState(p, "minimized"));
                shown = false;
                return null;
            }, 15);
        } catch (RuntimeException e) {
            log.debug("Hiding the browser window: {}", e.toString());
        }
    }

    /** DevTools: the window state is "normal" or "minimized" (a minimised page keeps running, as the flags above prevent throttling). */
    private void setWindowState(Page page, String state) {
        try {
            com.microsoft.playwright.CDPSession session = context.newCDPSession(page);
            try {
                com.google.gson.JsonObject window = session.send("Browser.getWindowForTarget");
                com.google.gson.JsonObject params = new com.google.gson.JsonObject();
                params.addProperty("windowId", window.get("windowId").getAsInt());
                com.google.gson.JsonObject bounds = new com.google.gson.JsonObject();
                bounds.addProperty("windowState", state);
                params.add("bounds", bounds);
                session.send("Browser.setWindowBounds", params);
            } finally {
                session.detach();
            }
        } catch (RuntimeException e) {
            log.info("Could not change the browser window state ({}); it stays as it is", e.getClass().getSimpleName());
        }
    }

    /** Brings a job's tab to the front (SPEC 12: "Focus tab"). */
    public void bringToFront(long jobId) {
        withTab(jobId, page -> {
            page.bringToFront();
            return null;
        });
    }
}
