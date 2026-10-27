package se.caibirch.jobpilot.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.SelectOption;

/**
 * The single choke point for everything the app does to an external page (CLAUDE.md hard rules 1 to 3, SPEC 9.3).
 * <p>
 * Allowed: fill, select, check, upload on fields found by the extractor, and a click for one of two named
 * purposes: the necessary-only cookie button and a suggestion the executor itself just opened. Everything else is
 * refused here, in code. No other class may call {@code click}, {@code press}, {@code evaluate} and similar on a
 * page (a source-scan test fails the build if one does). Enter is never pressed because it can submit a form.
 */
public final class BrowserGuard {

    /** Why a click is allowed. M6 adds exactly one more: the Spara button of the aktivitetsrapport. */
    public enum ClickPurpose { COOKIE_NECESSARY, AUTOCOMPLETE_OPTION }

    /** @param done true when the action happened; otherwise {@code reason} says why it was refused or failed */
    public record Outcome(boolean done, String reason) {
        static Outcome ok() {
            return new Outcome(true, null);
        }

        static Outcome refused(String reason) {
            return new Outcome(false, reason);
        }
    }

    private static final Pattern COOKIE_WORDS = Pattern.compile("cookie|kakor|spårning|tracking", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Pattern SAFE_ID = Pattern.compile("^(f\\d+-)?jp-\\d+$");
    private static final int CLICK_TIMEOUT_MS = 3_000;
    private static final int SUGGESTION_WAIT_MS = 1_500;

    private final Page page;

    public BrowserGuard(Page page) {
        this.page = page;
    }

    // ---- fields ----

    public Outcome fill(FormField f, String value) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!f.isText()) {
            return Outcome.refused("not a text field");
        }
        Locator l = locator(f);
        try {
            l.fill(value, new Locator.FillOptions().setTimeout(5_000));
            if (f.autocomplete()) {
                Outcome picked = chooseSuggestion(f, value);
                if (!picked.done()) {
                    return picked;
                }
            }
            return Outcome.ok();
        } catch (PlaywrightException e) {
            return Outcome.refused("could not fill: " + first(e));
        }
    }

    public Outcome select(FormField f, String valueOrLabel) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!"select".equals(f.tag())) {
            return Outcome.refused("not a select");
        }
        try {
            locator(f).selectOption(new SelectOption().setLabel(valueOrLabel), new Locator.SelectOptionOptions().setTimeout(5_000));
            return Outcome.ok();
        } catch (PlaywrightException byLabel) {
            try {
                locator(f).selectOption(new SelectOption().setValue(valueOrLabel), new Locator.SelectOptionOptions().setTimeout(5_000));
                return Outcome.ok();
            } catch (PlaywrightException e) {
                return Outcome.refused("no such option: " + valueOrLabel);
            }
        }
    }

    /** Ticks or unticks a checkbox, or picks a radio. Consent-like controls are refused both ways. */
    public Outcome check(FormField f, boolean checked) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!f.isChoice()) {
            return Outcome.refused("not a checkbox or radio");
        }
        if ("radio".equals(f.type()) && !checked) {
            return Outcome.refused("a radio button cannot be unticked");
        }
        try {
            Locator l = locator(f);
            if (checked) {
                l.check(new Locator.CheckOptions().setTimeout(5_000));
            } else {
                l.uncheck(new Locator.UncheckOptions().setTimeout(5_000));
            }
            return Outcome.ok();
        } catch (PlaywrightException e) {
            return Outcome.refused("could not change the box: " + first(e));
        }
    }

    public Outcome upload(FormField f, Path file) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!"file".equals(f.type())) {
            return Outcome.refused("not a file input");
        }
        if (file == null || !Files.isRegularFile(file)) {
            return Outcome.refused("the document file does not exist");
        }
        try {
            locator(f).setInputFiles(file, new Locator.SetInputFilesOptions().setTimeout(10_000));
            return Outcome.ok();
        } catch (PlaywrightException e) {
            return Outcome.refused("could not upload: " + first(e));
        }
    }

    /** The value now in the field, or null when it cannot be read (for example some file inputs). */
    public String currentValue(FormField f) {
        try {
            Locator l = locator(f);
            if (l.count() != 1) {
                return null;
            }
            return f.isChoice() ? String.valueOf(l.isChecked()) : l.inputValue(new Locator.InputValueOptions().setTimeout(2_000));
        } catch (PlaywrightException e) {
            return null;
        }
    }

    // ---- clicks ----

    /** Clicks "only necessary" on a cookie banner if there is one. Returns whether it clicked. */
    public boolean dismissCookieBanner() {
        Locator candidates = page.locator(":is([id*=cookie i], [class*=cookie i], [id*=consent i], [class*=consent i], "
                + "[id*=gdpr i], [class*=gdpr i], [role=dialog], [aria-modal=true]) :is(button, a, [role=button], input[type=button])");
        int n;
        try {
            n = Math.min(candidates.count(), 40);
        } catch (PlaywrightException e) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            Locator c = candidates.nth(i);
            try {
                if (c.isVisible() && click(c, ClickPurpose.COOKIE_NECESSARY).done()) {
                    return true;
                }
            } catch (PlaywrightException ignored) {
                // gone while we looked
            }
        }
        return false;
    }

    /** Picks the suggestion that equals {@code text}, in the list the field just opened. */
    Outcome chooseSuggestion(FormField f, String text) {
        Frame frame = frame(f);
        Locator options = frame.locator("[role=option]");
        try {
            options.first().waitFor(new Locator.WaitForOptions().setTimeout(SUGGESTION_WAIT_MS));
        } catch (PlaywrightException e) {
            return Outcome.refused("typed the text, but no suggestion appeared; pick it yourself");
        }
        int n = Math.min(options.count(), 50);
        for (int i = 0; i < n; i++) {
            Locator option = options.nth(i);
            String t = option.innerText().strip();
            if (t.equalsIgnoreCase(text.strip())) {
                Outcome o = click(option, ClickPurpose.AUTOCOMPLETE_OPTION);
                return o.done() ? o : Outcome.refused("typed the text, but " + o.reason());
            }
        }
        return Outcome.refused("typed the text, but no suggestion matched exactly; pick it yourself");
    }

    /**
     * The only {@code click} in the application. The purpose decides what may be clicked, and the submit-like
     * deny list (SPEC 9.3) applies on top of it, checked against every text the element carries.
     */
    Outcome click(Locator target, ClickPurpose purpose) {
        try {
            String own = nz(target.innerText(new Locator.InnerTextOptions().setTimeout(1_500)));
            String value = nz(target.getAttribute("value"));
            String aria = nz(target.getAttribute("aria-label"));
            String title = nz(target.getAttribute("title"));
            String type = nz(target.getAttribute("type")).toLowerCase(Locale.ROOT);
            String role = nz(target.getAttribute("role")).toLowerCase(Locale.ROOT);
            String ancestor = nz(target.locator("xpath=ancestor-or-self::*[self::button or self::a or @role='button' or @role='option' or self::li][1]")
                    .first().innerText(new Locator.InnerTextOptions().setTimeout(1_500)));
            if (type.equals("submit")) {
                return Outcome.refused("refused: this is a submit button");
            }
            for (String t : List.of(own, value, aria, title, ancestor)) {
                if (GuardPatterns.isSubmitLike(t)) {
                    return Outcome.refused("refused: '" + t.strip() + "' looks like a submit or apply button");
                }
            }
            if (target.locator("xpath=ancestor::form[.//input[@type='password']]").count() > 0) {
                return Outcome.refused("refused: inside a login form");
            }
            String label = String.join(" ", own, value, aria, title);
            switch (purpose) {
                case COOKIE_NECESSARY -> {
                    if (!GuardPatterns.COOKIE_NECESSARY.matcher(label).find()) {
                        return Outcome.refused("not a necessary-only cookie button");
                    }
                    // "Neka" in a modal about something else (cancel the application?) is not a cookie button
                    String container = nz(target.locator("xpath=ancestor::*[self::div or self::section or self::aside or self::dialog "
                            + "or @role='dialog' or @role='alertdialog' or @aria-modal='true'][1]").first()
                            .innerText(new Locator.InnerTextOptions().setTimeout(1_500)));
                    if (!COOKIE_WORDS.matcher(container).find()) {
                        return Outcome.refused("not inside a cookie notice");
                    }
                }
                case AUTOCOMPLETE_OPTION -> {
                    boolean option = role.equals("option") || target.locator("xpath=ancestor::*[@role='listbox']").count() > 0;
                    if (!option) {
                        return Outcome.refused("not a suggestion in a list");
                    }
                }
            }
            target.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            return Outcome.ok();
        } catch (PlaywrightException e) {
            return Outcome.refused("could not click: " + first(e));
        }
    }

    // ---- checks ----

    private Outcome checkField(FormField f) {
        if (f.id() == null || !SAFE_ID.matcher(f.id()).matches()) {
            return Outcome.refused("unsafe field id");
        }
        String type = f.type() == null ? "" : f.type().toLowerCase(Locale.ROOT);
        if (List.of("password", "hidden", "submit", "button", "image", "reset").contains(type)) {
            return Outcome.refused("refused: " + type + " fields are never touched");
        }
        if (GuardPatterns.CREDENTIAL.matcher(f.ownText()).find()) {
            return Outcome.refused("refused: looks like a login or BankID field");
        }
        if (GuardPatterns.isConsent(f.consentText())) {
            return Outcome.refused("refused: consent, terms and privacy choices are left for you");
        }
        if (f.disabled()) {
            return Outcome.refused("the field is disabled or read-only");
        }
        try {
            Locator l = locator(f);
            if (l.count() != 1) {
                return Outcome.refused("the field is gone (the page changed)");
            }
            String live = nz(l.getAttribute("type")).toLowerCase(Locale.ROOT);
            if (live.equals("password") || live.equals("hidden") || live.equals("submit")) {
                return Outcome.refused("refused: the field turned into a " + live + " field");
            }
        } catch (PlaywrightException e) {
            return Outcome.refused("the field cannot be reached: " + first(e));
        }
        return null;
    }

    private Locator locator(FormField f) {
        return frame(f).locator("[data-jp-id=\"" + f.id() + "\"]");
    }

    private Frame frame(FormField f) {
        List<Frame> frames = page.frames();
        return f.frame() < frames.size() ? frames.get(f.frame()) : page.mainFrame();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String first(PlaywrightException e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
