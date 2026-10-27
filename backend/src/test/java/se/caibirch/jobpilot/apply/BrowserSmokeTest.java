package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import org.junit.jupiter.api.Test;

/**
 * Proves headless Chromium starts on this machine. Not skipped when it fails: the guard tests protect a
 * hard rule, so a missing browser must be loud.
 */
class BrowserSmokeTest {

    static final String INSTALL = "cd backend && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "
            + "-Dexec.args=\"install chromium\"";

    @Test
    void headlessChromiumLaunchesWithATemporaryProfile() throws Exception {
        Path profile = Files.createTempDirectory("jobpilot-smoke-profile");
        try (Playwright pw = Playwright.create()) {
            try (BrowserContext ctx = pw.chromium().launchPersistentContext(profile,
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(true))) {
                var page = ctx.newPage();
                page.setContent("<html><head><title>JobPilot fixture</title></head><body><input id=a value=x></body></html>");
                assertThat(page.title()).isEqualTo("JobPilot fixture");
                assertThat(page.locator("#a").inputValue()).isEqualTo("x");
            }
        } catch (PlaywrightException e) {
            throw new AssertionError("Chromium could not start. Install it once with: " + INSTALL + "\nCause: " + e.getMessage(), e);
        }
    }
}
