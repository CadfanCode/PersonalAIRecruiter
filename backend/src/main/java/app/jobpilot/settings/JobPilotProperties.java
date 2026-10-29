package app.jobpilot.settings;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Static configuration from application.yml. Editable settings (SPEC 13) move to the database
 * with the Settings page.
 */
@ConfigurationProperties(prefix = "jobpilot")
public record JobPilotProperties(Path dataDir, List<String> allowedOrigins, Claude claude, Search search,
                                 JobTech jobtech, Browser browser, Aktivitet aktivitet, Manual manual) {

    public record Claude(String path, String model, int timeoutSeconds, int webTimeoutSeconds, int concurrency) {

        /**
         * The executable to run. A bare name such as "claude" is looked up on PATH and in the usual per-user install
         * directories, because a desktop launcher starts the app with a PATH that lacks ~/.local/bin.
         */
        public String executable() {
            if (path == null || path.contains("/") || path.contains("\\")) {
                return path;
            }
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            List<String> dirs = new java.util.ArrayList<>(List.of(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)));
            String home = System.getProperty("user.home");
            dirs.addAll(List.of(home + "/.local/bin", home + "/.claude/local", home + "/.npm-global/bin", home + "/.bun/bin",
                    "/usr/local/bin", "/opt/homebrew/bin"));
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                dirs.add(appData + "/npm");
            }
            List<String> names = windows ? List.of(path + ".exe", path + ".cmd", path) : List.of(path);
            for (String dir : dirs) {
                for (String name : names) {
                    if (!dir.isBlank() && java.nio.file.Files.isExecutable(Path.of(dir, name))) {
                        return Path.of(dir, name).toString();
                    }
                }
            }
            return path;
        }
    }

    public record Search(int defaultMaxAgeDays, int defaultLimit, int resultsTopN) {
    }

    /** apiKey is optional: the live API needs none (SPEC 6.1). */
    public record JobTech(String baseUrl, String taxonomyUrl, String apiKey, int requestsPerSecond) {
    }

    /**
     * Adding a job by its link. {@code pasteOnlyHosts} are sites that discourage bots (LinkedIn): JobPilot never fetches them
     * and never opens them in its automated browser; the user pastes the ad text and applies there by hand.
     * {@code allowPrivateHosts} is for tests only: by default only public web addresses are fetched.
     */
    public record Manual(List<String> pasteOnlyHosts, boolean allowPrivateHosts, int maxPageBytes, int fetchTimeoutSeconds) {

        public Manual {
            pasteOnlyHosts = pasteOnlyHosts == null ? List.of("linkedin.com") : List.copyOf(pasteOnlyHosts);
            maxPageBytes = maxPageBytes <= 0 ? 2_000_000 : maxPageBytes;
            fetchTimeoutSeconds = fetchTimeoutSeconds <= 0 ? 15 : fetchTimeoutSeconds;
        }
    }

    /**
     * The apply browser (SPEC 9.1). file: URLs are only for fixtures in tests. {@code background}: the window runs off-screen and is
     * shown only when the user has to act (sign-in, review, taking over); it has no effect when {@code headless}.
     */
    public record Browser(boolean headless, int maxTabs, boolean allowFileUrls, int suggestionWaitMillis, boolean background,
                          int findFormSeconds) {

        @ConstructorBinding
        public Browser {
            findFormSeconds = findFormSeconds <= 0 ? 240 : findFormSeconds;
        }

        public Browser(boolean headless, int maxTabs, boolean allowFileUrls, int suggestionWaitMillis) {
            this(headless, maxTabs, allowFileUrls, suggestionWaitMillis, true, 240);
        }
    }

    /**
     * The only two addresses the aktivitetsrapport automation may open (SPEC 10). Tests point them at fixtures; the
     * Spara button is only ever clicked on {@code formUrl}.
     */
    public record Aktivitet(String formUrl, String listUrl) {
    }

    /** The one Spring binds from application.yml; the shorter constructor below is for tests. */
    @ConstructorBinding
    public JobPilotProperties {
    }

    /** Defaults for everything beyond the first three settings; used by tests that only care about those. */
    public JobPilotProperties(Path dataDir, List<String> allowedOrigins, Claude claude) {
        this(dataDir, allowedOrigins, claude, new Search(7, 50, 10),
                new JobTech("http://localhost:0", "http://localhost:0", "", 5), new Browser(true, 5, false, 5_000),
                new Aktivitet("https://arbetsformedlingen.se/for-arbetssokande/mina-sidor/aktivitetsrapportera/lagg-till-aktivitet",
                        "https://arbetsformedlingen.se/for-arbetssokande/mina-sidor/aktivitetsrapportera"),
                new Manual(null, false, 0, 0));
    }

    public Path browserProfileDir() {
        return dataDir.resolve("browser-profile");
    }

    public Path claudeWorkDir() {
        return dataDir.resolve("claude-work");
    }
}
