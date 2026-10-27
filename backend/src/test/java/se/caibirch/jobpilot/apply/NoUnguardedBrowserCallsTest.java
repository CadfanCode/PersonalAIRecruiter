package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Proof that CLAUDE.md hard rules 1 to 3 are enforced in code: only {@code BrowserGuard} may act on an element
 * (and click exactly once, for a named purpose), only {@code PageScripts} may run page scripts, and no class
 * anywhere may press keys, dispatch events, submit forms or move the mouse. Adding such a call fails the build.
 */
class NoUnguardedBrowserCallsTest {

    static final Path MAIN = Path.of("src/main/java");

    /** Raw Playwright operations nobody may use outside the two allowed classes. */
    static final Pattern RAW_ACTIONS = Pattern.compile(
            "\\.(click|dblclick|press|pressSequentially|keyboard|mouse|touchscreen|dispatchEvent|requestSubmit|tap|dragTo|setChecked"
                    + "|evaluate|evaluateHandle|evaluateAll|evalOnSelector\\w*|addInitScript|addScriptTag|exposeFunction|route|hover|focus"
                    + "|waitForFunction|setContent|locator|getBy(Role|Text|Label|Placeholder|AltText|Title|TestId)|frameLocator|querySelector\\w*|selectOption|uncheck|setInputFiles)\\s*\\(");

    /** fill and check are fine on the guard (BrowserGuard.fill, guard.check) but nowhere else, including Page.fill and Page.check. */
    static final Pattern GUARDED_ONLY = Pattern.compile("(?<!guard)(?<!Guard)\\.(fill|check)\\s*\\(");

    /** Types that give element-level access. */
    static final Pattern ELEMENT_TYPES = Pattern.compile(
            "import\\s+com\\.microsoft\\.playwright\\.(Locator|ElementHandle|JSHandle|Keyboard|Mouse|Touchscreen|FrameLocator)\\s*;");

    static final Pattern FORM_SUBMIT = Pattern.compile("(page|frame|form|locator|element)\\w*\\.submit\\s*\\(", Pattern.CASE_INSENSITIVE);

    static String code(Path file) throws IOException {
        String src = Files.readString(file);
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static boolean is(Path p, String name) {
        return p.getFileName().toString().equals(name);
    }

    @Test
    void onlyTheGuardAndTheScriptRunnerTouchElementsOrRunScripts() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (is(file, "BrowserGuard.java") || is(file, "PageScripts.java")) {
                continue;
            }
            String code = code(file);
            if (RAW_ACTIONS.matcher(code).find()) {
                offenders.add(file + " calls a raw browser action: " + RAW_ACTIONS.matcher(code).results().findFirst().orElseThrow().group());
            }
            // fill and check only matter in files that can reach a page at all
            if (code.contains("com.microsoft.playwright") && GUARDED_ONLY.matcher(code).find()) {
                offenders.add(file + " calls fill or check on something that is not the guard");
            }
            if (ELEMENT_TYPES.matcher(code).find()) {
                offenders.add(file + " imports an element-level Playwright type");
            }
            if (FORM_SUBMIT.matcher(code).find()) {
                offenders.add(file + " submits a form");
            }
        }
        assertThat(offenders).as("browser actions outside BrowserGuard and PageScripts").isEmpty();
    }

    @Test
    void theGuardClicksExactlyOnceAndNeverPressesKeysOrSubmits() throws IOException {
        String guard = code(MAIN.resolve("se/caibirch/jobpilot/apply/BrowserGuard.java"));

        assertThat(Pattern.compile("\\.click\\s*\\(").matcher(guard).results().count()).as("click calls in BrowserGuard").isEqualTo(1);
        assertThat(guard).doesNotContainPattern("\\.(press|pressSequentially|keyboard|mouse|dispatchEvent|requestSubmit|dblclick|tap|dragTo|evaluate|evaluateHandle|addInitScript|route|hover)\\s*\\(");
        assertThat(FORM_SUBMIT.matcher(guard).find()).isFalse();
        // the only enum values a click may have: M6 adds AKTIVITET_SPARA and nothing else
        assertThat(guard).contains("enum ClickPurpose { COOKIE_NECESSARY, AUTOCOMPLETE_OPTION }");
    }

    @Test
    void pageScriptsRunsOnlyTheTwoFixedScriptsFromTheClasspath() throws IOException {
        String scripts = code(MAIN.resolve("se/caibirch/jobpilot/apply/PageScripts.java"));

        assertThat(Pattern.compile("\\.evaluate\\s*\\(").matcher(scripts).results().count()).isEqualTo(3);
        assertThat(scripts).contains("read(\"apply/extract-fields.js\")", "read(\"apply/detect-state.js\")");
        assertThat(scripts).doesNotContainPattern("\\.(click|press|keyboard|mouse|dispatchEvent|submit)\\s*\\(");
        // every call starts with one of the two fixed scripts; nothing is concatenated from other input
        assertThat(Pattern.compile("\\.evaluate\\s*\\((EXTRACT|DETECT)\\b").matcher(scripts).results().count()).isEqualTo(3);
    }

    @Test
    void theScriptFilesThemselvesNeverClickTypeOrSubmit() throws IOException {
        for (String name : List.of("extract-fields.js", "detect-state.js")) {
            String js = Files.readString(Path.of("src/main/resources/apply/" + name)).replaceAll("(?m)^\\s*//.*$", "");
            assertThat(js).as(name).doesNotContainPattern("\\.(click|submit|requestSubmit|dispatchEvent|focus)\\s*\\(")
                    .doesNotContainPattern("\\.(value|checked)\\s*=[^=]").doesNotContain("fetch(").doesNotContain("XMLHttpRequest");
        }
    }

    @Test
    void theScannerItselfCatchesAForbiddenCall() {
        assertThat(RAW_ACTIONS.matcher("locator.click();").find()).isTrue();
        assertThat(RAW_ACTIONS.matcher("page.keyboard().press(\"Enter\")").find()).isTrue();
        assertThat(RAW_ACTIONS.matcher("frame.evaluate(script)").find()).isTrue();
        assertThat(RAW_ACTIONS.matcher("executor.submit(task)").find()).isFalse();
        assertThat(RAW_ACTIONS.matcher("guard.fill(field, value)").find()).isFalse();
        assertThat(FORM_SUBMIT.matcher("page.locator(\"form\").submit()").find()).isFalse(); // caught by RAW_ACTIONS.. locator(...)
        assertThat(FORM_SUBMIT.matcher("form.submit()").find()).isTrue();
        for (String call : new String[] {"page.locator(\"#gdpr\").check()", "page.getByRole(role)", "page.frameLocator(f)", "page.querySelector(s)",
                "page.waitForFunction(js)", "page.setContent(html)", "page.addScriptTag(o)", "locator.evaluateAll(js)", "page.evalOnSelector(s, js)",
                "x.selectOption(\"a\")", "x.setInputFiles(p)", "x.uncheck()"}) {
            assertThat(RAW_ACTIONS.matcher(call).find()).as(call).isTrue();
        }
        for (String call : new String[] {"page.check(\"#gdpr\")", "page.fill(\"#n\", \"x\")", "locator.fill(v)", "locator.check()"}) {
            assertThat(GUARDED_ONLY.matcher(call).find()).as(call).isTrue();
        }
        for (String call : new String[] {"guard.fill(f, v)", "guard.check(f, true)"}) {
            assertThat(GUARDED_ONLY.matcher(call).find()).as(call).isFalse();
        }
        assertThat(ELEMENT_TYPES.matcher("import com.microsoft.playwright.Locator;").find()).isTrue();
        assertThat(ELEMENT_TYPES.matcher("import com.microsoft.playwright.Page;").find()).isFalse();
    }
}
