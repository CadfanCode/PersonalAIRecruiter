package se.caibirch.jobpilot.apply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/** One headless Chromium with a temporary profile per test class, a fresh page per test, and local HTML fixtures only. */
abstract class PlaywrightTest {

    static Playwright playwright;
    static BrowserContext context;
    static Path fixtures = Path.of("src/test/resources/fixtures/apply").toAbsolutePath();

    Page page;

    @BeforeAll
    static void startBrowser() throws IOException {
        playwright = Playwright.create();
        context = playwright.chromium().launchPersistentContext(Files.createTempDirectory("jobpilot-test-profile"),
                new BrowserType.LaunchPersistentContextOptions().setHeadless(true));
    }

    @AfterAll
    static void stopBrowser() {
        context.close();
        playwright.close();
    }

    @BeforeEach
    void newPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    static String url(String fixture) {
        return fixtures.resolve(fixture).toUri().toString();
    }

    void open(String fixture) {
        page.navigate(url(fixture));
    }

    /** The fixture's submit trap: true if any form was submitted. */
    boolean submitted() {
        for (var frame : page.frames()) {
            if (Boolean.TRUE.equals(frame.evaluate("window.__submitted === true"))) {
                return true;
            }
        }
        return false;
    }

    /** Texts of every button-like element that was clicked, in every frame. */
    @SuppressWarnings("unchecked")
    List<String> clicks() {
        List<String> all = new java.util.ArrayList<>();
        for (var frame : page.frames()) {
            Object c = frame.evaluate("window.__clicks || []");
            all.addAll((List<String>) c);
        }
        return all;
    }

    static FormField byLabel(List<FormField> fields, String label) {
        return fields.stream().filter(f -> f.allText().toLowerCase().contains(label.toLowerCase())).findFirst()
                .orElseThrow(() -> new AssertionError("No field with '" + label + "' in " + fields.stream().map(FormField::allText).toList()));
    }

    static Path tempPdf(String name) throws IOException {
        Path dir = Files.createTempDirectory("jobpilot-docs-test");
        Path file = dir.resolve(name);
        Files.write(file, "%PDF-1.4 test".getBytes());
        return file;
    }
}
