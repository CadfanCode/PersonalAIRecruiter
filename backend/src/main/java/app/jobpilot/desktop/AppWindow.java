package app.jobpilot.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opens the UI in its own chromeless browser window ({@code --app=URL}) with a private profile, and tells when the
 * window has been closed. Any Chromium-based browser will do; Playwright's Chromium (which the form filling needs
 * anyway) is the fallback, so the user never has to install a browser.
 */
final class AppWindow {

    private final Path profile;
    private Process process;

    AppWindow(Path profile) {
        this.profile = profile;
    }

    /** A Chromium-based browser installed on this machine, in order of preference. */
    static Optional<Path> installedBrowser() {
        String override = System.getenv("JOBPILOT_BROWSER");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isExecutable(p) ? Optional.of(p) : onPath(override);
        }
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String root : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (root != null) {
                    candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
                    candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                    candidates.add(root + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
                }
            }
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            candidates.add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
            candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
        } else {
            for (String name : new String[] {"brave", "brave-browser", "chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "microsoft-edge"}) {
                Optional<Path> found = onPath(name);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return candidates.stream().map(Path::of).filter(Files::isExecutable).findFirst();
    }

    private static Optional<Path> onPath(String name) {
        String path = System.getenv().getOrDefault("PATH", "");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, name))) {
                return Optional.of(Path.of(dir, name));
            }
        }
        return Optional.empty();
    }

    void open(Path browser, String url) throws IOException {
        Files.createDirectories(profile);
        process = new ProcessBuilder(browser.toString(), "--app=" + url, "--user-data-dir=" + profile, "--no-first-run",
                "--no-default-browser-check", "--window-size=1440,900").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * Blocks until the window is gone. A browser that hands the window to a running instance exits at once, so the wait
     * continues while any process still uses this window's private profile.
     */
    void awaitClosed() throws InterruptedException {
        if (process != null) {
            process.waitFor();
        }
        String marker = "--user-data-dir=" + profile;
        while (ProcessHandle.allProcesses().anyMatch(h -> h.info().commandLine().map(c -> c.contains(marker)).orElse(false))) {
            Thread.sleep(1000);
        }
    }
}
