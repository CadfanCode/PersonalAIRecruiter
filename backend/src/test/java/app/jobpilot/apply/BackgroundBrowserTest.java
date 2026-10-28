package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import app.jobpilot.settings.JobPilotProperties;

/**
 * A headed Chromium on a real display: it starts minimised, can be shown for the user and minimised again, and a page behind it
 * keeps running at full speed. Needs an X display (DISPLAY), so it is skipped on a machine without one.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class BackgroundBrowserTest {

    static String windowState(BrowserService browser, long job) {
        return browser.withTab(job, page -> {
            CDPSession session = page.context().newCDPSession(page);
            try {
                int id = session.send("Browser.getWindowForTarget").get("windowId").getAsInt();
                JsonObject params = new JsonObject();
                params.addProperty("windowId", id);
                return session.send("Browser.getWindowBounds", params).getAsJsonObject("bounds").get("windowState").getAsString();
            } finally {
                session.detach();
            }
        });
    }

    static String title(BrowserService browser) {
        String title = browser.withTab(7, p -> p.title());
        return title;
    }

    static long ticks(BrowserService browser) {
        Number n = browser.withTab(7, p -> (Number) p.evaluate("window.ticks"));
        return n.longValue();
    }

    @Test
    void theWindowStartsMinimisedIsShownForTheUserAndMinimisedAgainWhileThePageKeepsRunning() throws Exception {
        JobPilotProperties base = new JobPilotProperties(Files.createTempDirectory("jp-bg"), List.of(), new JobPilotProperties.Claude("claude", "", 5, 5, 2));
        JobPilotProperties props = new JobPilotProperties(base.dataDir(), base.allowedOrigins(), base.claude(), base.search(), base.jobtech(),
                new JobPilotProperties.Browser(false, 5, true, 5_000, true, 90), base.aktivitet(), base.manual());
        BrowserService browser = new BrowserService(props);
        try {
            assertThat(browser.backgroundMode()).isTrue();
            browser.openTab(7, "data:text/html,<title>t</title><input id=a><script>window.ticks=0;setInterval(()=>window.ticks++,100)</script>");
            assertThat(browser.windowShown()).isFalse();
            assertThat(windowState(browser, 7)).as("starts minimised").isEqualTo("minimized");

            // a hidden page still reads, and keeps its timers running at full speed
            assertThat(title(browser)).isEqualTo("t");
            long before = ticks(browser);
            browser.withTab(7, p -> {
                p.waitForTimeout(1_000);
                return null;
            });
            assertThat(ticks(browser) - before).as("no throttling while minimised").isGreaterThan(7);

            browser.reveal(7);
            assertThat(browser.windowShown()).isTrue();
            assertThat(windowState(browser, 7)).isEqualTo("normal");

            browser.hide();
            assertThat(browser.windowShown()).isFalse();
            assertThat(windowState(browser, 7)).isEqualTo("minimized");
            assertThat(title(browser)).isEqualTo("t");
        } finally {
            browser.destroy();
        }
    }
}
