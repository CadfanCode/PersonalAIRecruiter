package app.jobpilot.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.microsoft.playwright.Playwright;

/** The Chromium that Playwright downloads once (about 150 MB) when no Chromium-based browser is installed. */
final class PlaywrightBrowser {

    private PlaywrightBrowser() {
    }

    static Optional<Path> executable() {
        try (Playwright playwright = Playwright.create()) {
            Path path = Path.of(playwright.chromium().executablePath());
            return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
