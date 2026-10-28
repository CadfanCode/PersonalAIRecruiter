package se.caibirch.jobpilot.desktop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import se.caibirch.jobpilot.JobPilotApplication;

/**
 * The entry point of the installed desktop app (ADR 0012): one process starts the embedded database and the backend,
 * shows the UI in its own window, and stops everything when that window is closed. A second launch only opens another
 * window on the running instance. Development still uses {@link JobPilotApplication} directly.
 */
public final class DesktopLauncher {

    private static final int FIRST_PORT = 18080;

    private DesktopLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = dataDir();
        Files.createDirectories(dataDir);
        Path lockFile = dataDir.resolve("launcher.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                openOnRunningInstance(dataDir);
                System.exit(0);
                return;
            }
            int port = freePort();
            Files.writeString(dataDir.resolve("port"), String.valueOf(port));
            System.setProperty("server.port", String.valueOf(port));
            System.setProperty("jobpilot.data-dir", dataDir.toString());
            ConfigurableApplicationContext context = SpringApplication.run(JobPilotApplication.class, args);
            try {
                showWindow("http://127.0.0.1:" + port, dataDir);
            } finally {
                System.exit(SpringApplication.exit(context));
            }
        }
    }

    static Path dataDir() {
        String configured = System.getenv("JOBPILOT_DATA_DIR");
        return configured != null && !configured.isBlank() ? Path.of(configured) : Path.of(System.getProperty("user.home"), ".jobpilot");
    }

    private static void showWindow(String url, Path dataDir) throws IOException, InterruptedException {
        Optional<Path> browser = AppWindow.installedBrowser();
        if (browser.isEmpty()) {
            browser = PlaywrightBrowser.executable();
        }
        if (browser.isEmpty()) {
            System.err.println("No Chromium-based browser found. Open " + url + " in any browser; stop JobPilot with Ctrl+C.");
            Thread.currentThread().join();
            return;
        }
        AppWindow window = new AppWindow(dataDir.resolve("app-window"));
        window.open(browser.get(), url);
        window.awaitClosed();
    }

    private static void openOnRunningInstance(Path dataDir) throws IOException, InterruptedException {
        Path portFile = dataDir.resolve("port");
        if (!Files.isReadable(portFile)) {
            return;
        }
        String url = "http://127.0.0.1:" + Files.readString(portFile).trim();
        if (!reachable(url + "/api/runs")) {
            return;
        }
        Optional<Path> browser = AppWindow.installedBrowser();
        if (browser.isPresent()) {
            new AppWindow(dataDir.resolve("app-window")).open(browser.get(), url);
        }
    }

    private static boolean reachable(String url) throws InterruptedException {
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        for (int port = FIRST_PORT; port < FIRST_PORT + 40; port++) {
            try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return port;
            } catch (IOException busy) {
                // try the next one
            }
        }
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
