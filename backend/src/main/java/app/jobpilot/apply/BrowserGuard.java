package app.jobpilot.apply;

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
 * Allowed: fill, choose, check, upload on fields named by a snapshot ref, going to the application form from an ad page, and a
 * click for a few named purposes: the necessary-only cookie button, a drop-down and its option, the link or button that opens the
 * application form, and the Next/Continue button of a multi-step form (never one that sends it, and never on a review page). Everything else is refused here, in code. No other class may call {@code click}, {@code press}, {@code evaluate} and similar on a
 * page (a source-scan test fails the build if one does). Enter is never pressed because it can submit a form.
 */
public final class BrowserGuard {

    /**
     * Why a click is allowed. AKTIVITET_SPARA is the Spara button of the aktivitetsrapport form. OPEN_APPLICATION_FORM is the
     * "Ansök" or "Apply" link or button of an ad page that leads to the form: it is refused on a page that already is a form,
     * after anything was filled, for a submit button, inside a form that has inputs, and for any label with a final-submit word.
     */
    public enum ClickPurpose { COOKIE_NECESSARY, AUTOCOMPLETE_OPTION, AKTIVITET_SPARA, OPEN_APPLICATION_FORM, ADVANCE_STEP, OPEN_DROPDOWN }

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
    /** A tagged file input ({@code jp-3}) or a snapshot ref ({@code e12}, {@code f1e4}). Nothing else is ever put into a locator. */
    static final Pattern SAFE_ID = Pattern.compile("^((f\\d+-)?jp-\\d+|(f\\d+)?e\\d+)$");
    private static final int CLICK_TIMEOUT_MS = 3_000;
    private static final int SUGGESTION_WAIT_MS = 1_500;
    private static final String SAVE_TEXT = "Spara";

    private static final Pattern SAFE_LIST_ID = Pattern.compile("^[A-Za-z0-9_:.\\-]{1,100}$");

    private final Page page;
    private final int suggestionWaitMs;
    private final String aktivitetFormUrl;

    public BrowserGuard(Page page) {
        this(page, SUGGESTION_WAIT_MS, null);
    }

    /**
     * @param suggestionWaitMs how long to wait for a suggestion list
     * @param aktivitetFormUrl the one page on which {@code AKTIVITET_SPARA} may click; null means never
     */
    public BrowserGuard(Page page, int suggestionWaitMs, String aktivitetFormUrl) {
        this.page = page;
        this.suggestionWaitMs = suggestionWaitMs;
        this.aktivitetFormUrl = aktivitetFormUrl;
    }

    // ---- saved portal login (CLAUDE.md rule 2, ADR 0016) ----

    /** What {@link #fillSavedLogin} did. Never carries a value from the page or the credentials. */
    public enum LoginResult { FILLED, HOST_MISMATCH, NOT_A_LOGIN_FORM, FAILED }

    private static final int LOGIN_FORM_MAX_INPUTS = 3;
    private static final String TEXT_INPUT = "input:visible:not([type=hidden]):not([type=password]):not([type=submit]):not([type=button])"
            + ":not([type=image]):not([type=reset]):not([type=checkbox]):not([type=radio]):not([type=file])";

    /**
     * Types a login the user chose to save for this portal: the username, then the password, nothing else. It refuses unless the
     * page's host is exactly {@code expectedHost} and the page shows one login form with exactly one password field and at most
     * three inputs (a sign-up form has a second password field or more inputs). It never clicks the login button and never
     * presses a key: the user presses "Log in" (rule 1). The values come only from the credential store and are never logged.
     */
    public LoginResult fillSavedLogin(String expectedHost, String username, String password) {
        String host = hostOf(page.url());
        if (host == null || expectedHost == null || !host.equalsIgnoreCase(expectedHost)) {
            return LoginResult.HOST_MISMATCH;
        }
        try {
            Locator passwords = page.locator("input[type=password]:visible");
            if (passwords.count() != 1) {
                return LoginResult.NOT_A_LOGIN_FORM;
            }
            Locator pw = passwords.first();
            Locator form = pw.locator("xpath=ancestor::form[1]");
            Locator scope = form.count() > 0 ? form : page.locator("body");
            Locator visibleInputs = scope.locator("input:visible:not([type=hidden]):not([type=submit]):not([type=button]):not([type=image])"
                    + ":not([type=reset]):not([type=checkbox]):not([type=radio])");
            Locator user = scope.locator(TEXT_INPUT);
            if (visibleInputs.count() > LOGIN_FORM_MAX_INPUTS || user.count() < 1) {
                return LoginResult.NOT_A_LOGIN_FORM;
            }
            user.first().fill(username, new Locator.FillOptions().setTimeout(5_000));
            pw.fill(password, new Locator.FillOptions().setTimeout(5_000));
            return LoginResult.FILLED;
        } catch (PlaywrightException e) {
            return LoginResult.FAILED; // the message could echo a value, so it is dropped
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
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
                Outcome picked = pickSuggestion(f, l, value, false);
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

    /**
     * Picks an option of a drop-down: a native {@code <select>} by its label, or a custom one (a combobox, a button that opens a
     * list) by opening it and clicking the option whose text matches. Only options that exist are ever picked.
     */
    public Outcome choose(FormField f, String valueOrLabel) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!"select".equals(f.tag())) {
            return Outcome.refused("not a drop-down");
        }
        if (valueOrLabel == null || valueOrLabel.isBlank()) {
            return Outcome.refused("no option to choose");
        }
        Locator l = locator(f);
        try {
            l.selectOption(new SelectOption().setLabel(valueOrLabel), new Locator.SelectOptionOptions().setTimeout(2_000));
            return Outcome.ok();
        } catch (PlaywrightException e) {
            if (!String.valueOf(e.getMessage()).contains("not a <select>")) {
                try {
                    l.selectOption(new SelectOption().setValue(valueOrLabel), new Locator.SelectOptionOptions().setTimeout(2_000));
                    return Outcome.ok();
                } catch (PlaywrightException again) {
                    return Outcome.refused("no such option: " + valueOrLabel);
                }
            }
        }
        Outcome opened = click(l, ClickPurpose.OPEN_DROPDOWN);
        if (!opened.done()) {
            return opened;
        }
        Outcome picked = pickSuggestion(f, l, valueOrLabel, false, true);
        if (!picked.done() && picked.reason().contains("no suggestion appeared")) {
            try { // a searchable drop-down lists nothing until something is typed
                l.fill(valueOrLabel, new Locator.FillOptions().setTimeout(2_000));
            } catch (PlaywrightException ignored) {
                return Outcome.refused("could not find the option '" + valueOrLabel + "'; choose it yourself");
            }
            picked = pickSuggestion(f, l, valueOrLabel, false, true);
        }
        return picked;
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

    /**
     * Types {@code typed} into an autocomplete field and clicks the suggestion that equals {@code exact}. When
     * {@code arrowDown} is set and no list appeared, one ArrowDown opens it (the ort field does this). Nothing else is
     * ever pressed, and Enter never.
     */
    public Outcome typeAndPick(FormField f, String typed, String exact, boolean arrowDown) {
        Outcome refusal = checkField(f);
        if (refusal != null) {
            return refusal;
        }
        if (!f.isText()) {
            return Outcome.refused("not a text field");
        }
        Locator l = locator(f);
        try {
            l.fill(typed, new Locator.FillOptions().setTimeout(5_000));
        } catch (PlaywrightException e) {
            return Outcome.refused("could not fill: " + first(e));
        }
        return pickSuggestion(f, l, exact, arrowDown);
    }

    private Outcome pickSuggestion(FormField f, Locator input, String text, boolean arrowDown) {
        return pickSuggestion(f, input, text, arrowDown, false);
    }

    /** {@code loose}: when no item equals the text, a single item that contains it (or is contained by it) is taken. */
    private Outcome pickSuggestion(FormField f, Locator input, String text, boolean arrowDown, boolean loose) {
        Frame frame = frame(f);
        String listId = listIdOf(input);
        // Only visible items count: a closed list from another field must not look like this field's suggestions.
        Locator options = frame.locator(listId == null ? "[role=option], [role=listbox] li, [role=menuitem]"
                : "[role=option], [role=listbox] li, [role=menuitem], [id=\"" + listId + "\"] li")
                .filter(new Locator.FilterOptions().setVisible(true));
        boolean open = appears(options);
        if (!open && arrowDown) {
            try {
                input.press("ArrowDown", new Locator.PressOptions().setTimeout(2_000));
            } catch (PlaywrightException e) {
                return Outcome.refused("could not open the list: " + first(e));
            }
            open = appears(options);
        }
        if (!open) {
            return Outcome.refused("typed the text, but no suggestion appeared; pick it yourself");
        }
        int n;
        try {
            n = Math.min(options.count(), 80);
        } catch (PlaywrightException e) {
            return Outcome.refused("the suggestions disappeared");
        }
        for (int i = 0; i < n; i++) {
            Locator option = options.nth(i);
            try {
                if (option.innerText().strip().equalsIgnoreCase(text.strip())) {
                    Outcome o = click(option, ClickPurpose.AUTOCOMPLETE_OPTION, listId);
                    return o.done() ? o : Outcome.refused("typed the text, but " + o.reason());
                }
            } catch (PlaywrightException ignored) {
                // the list changed while we read it
            }
        }
        if (loose) {
            String want = text.strip().toLowerCase(Locale.ROOT);
            Locator only = null;
            int matches = 0;
            for (int i = 0; i < n; i++) {
                try {
                    String have = options.nth(i).innerText().strip().toLowerCase(Locale.ROOT);
                    if (!have.isEmpty() && (have.contains(want) || want.contains(have))) {
                        only = options.nth(i);
                        matches++;
                    }
                } catch (PlaywrightException ignored) {
                    // the list changed while we read it
                }
            }
            if (matches == 1) {
                Outcome o = click(only, ClickPurpose.AUTOCOMPLETE_OPTION, listId);
                return o.done() ? o : Outcome.refused("found the option, but " + o.reason());
            }
        }
        return Outcome.refused("typed the text, but no suggestion matched exactly; pick it yourself");
    }

    private boolean appears(Locator options) {
        try {
            options.first().waitFor(new Locator.WaitForOptions().setTimeout(suggestionWaitMs));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** The id of the list an input points at with aria-controls or aria-owns, if it is a plain safe id. */
    private static String listIdOf(Locator input) {
        for (String attr : new String[] {"aria-controls", "aria-owns"}) {
            String v = input.getAttribute(attr);
            if (v != null && SAFE_LIST_ID.matcher(v.strip()).matches()) {
                return v.strip();
            }
        }
        return null;
    }

    // ---- the aktivitetsrapport form ----

    /**
     * Clicks Spara on the aktivitetsrapport form. All of these must hold: the guard was given the form's address, the
     * page is exactly that address (query and hash ignored), exactly one visible control reads exactly "Spara", and it
     * passes the submit-like deny list ("Skicka in" and "Spara och skicka in" never do).
     */
    public Outcome saveActivity() {
        if (aktivitetFormUrl == null) {
            return Outcome.refused("refused: no aktivitet form address configured");
        }
        if (!stripQueryAndHash(page.url()).equals(stripQueryAndHash(aktivitetFormUrl))) {
            return Outcome.refused("refused: not on the aktivitetsrapport form");
        }
        Locator candidates = page.locator("button, input[type=submit], input[type=button], [role=button]");
        List<Locator> exact = new java.util.ArrayList<>();
        try {
            int n = Math.min(candidates.count(), 80);
            for (int i = 0; i < n; i++) {
                Locator c = candidates.nth(i);
                if (c.isVisible() && SAVE_TEXT.equals(labelOf(c))) {
                    exact.add(c);
                }
            }
        } catch (PlaywrightException e) {
            return Outcome.refused("could not look for the Spara button: " + first(e));
        }
        if (exact.size() != 1) {
            return Outcome.refused("expected exactly one Spara button, found " + exact.size());
        }
        return click(exact.get(0), ClickPurpose.AKTIVITET_SPARA);
    }

    /** Waits until the page text contains the phrase and one of the names (the saved-activity toast). */
    public boolean waitForConfirmation(String phrase, List<String> names, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                String text = page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout(2_000));
                if (text.contains(phrase) && names.stream().anyMatch(n -> !n.isBlank() && text.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT)))) {
                    return true;
                }
                Thread.sleep(150);
            } catch (PlaywrightException e) {
                // the page is navigating; try again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String labelOf(Locator c) {
        String text = c.innerText(new Locator.InnerTextOptions().setTimeout(1_000)).strip();
        if (!text.isEmpty()) {
            return text;
        }
        String value = c.getAttribute("value");
        if (value != null && !value.isBlank()) {
            return value.strip();
        }
        String aria = c.getAttribute("aria-label");
        return aria == null ? "" : aria.strip();
    }

    static String stripQueryAndHash(String url) {
        int cut = url.length();
        for (char ch : new char[] {'?', '#'}) {
            int i = url.indexOf(ch);
            if (i >= 0) {
                cut = Math.min(cut, i);
            }
        }
        return url.substring(0, cut);
    }

    /**
     * The only {@code click} in the application. The purpose decides what may be clicked, and the submit-like
     * deny list (SPEC 9.3) applies on top of it, checked against every text the element carries.
     */
    Outcome click(Locator target, ClickPurpose purpose) {
        return click(target, purpose, null);
    }

    Outcome click(Locator target, ClickPurpose purpose, String listId) {
        try {
            String own = safeText(target);
            String value = nz(target.getAttribute("value"));
            String aria = nz(target.getAttribute("aria-label"));
            String title = nz(target.getAttribute("title"));
            String type = nz(target.getAttribute("type")).toLowerCase(Locale.ROOT);
            String role = nz(target.getAttribute("role")).toLowerCase(Locale.ROOT);
            String ancestor = safeText(target.locator("xpath=ancestor-or-self::*[self::button or self::a or @role='button' or @role='option' or self::li][1]").first());
            boolean advance = purpose == ClickPurpose.ADVANCE_STEP;
            // A Next button is very often a submit button of the step's own form; the label rules and the review-page rule below
            // stand in for the type check there. Every other purpose still refuses submit buttons.
            if (type.equals("submit") && purpose != ClickPurpose.AKTIVITET_SPARA && !advance) {
                return Outcome.refused("refused: this is a submit button");
            }
            // A <button> without a type inside a <form> is a submit button by default.
            if (purpose != ClickPurpose.AKTIVITET_SPARA && !advance && target.locator("xpath=self::button[not(@type)]/ancestor::form").count() > 0) {
                return Outcome.refused("refused: a button inside a form, which would submit it");
            }
            boolean opening = purpose == ClickPurpose.OPEN_APPLICATION_FORM || advance;
            for (String t : List.of(own, value, aria, title, ancestor)) {
                // Opening the form is the one purpose that may carry "Ansök" or "Apply", but never a sending word. "Apply now" is
                // too often the button that sends the application (a modal's last step), so a button with it is never pressed;
                // a link with that label is still loaded (a GET) by openApplication.
                if (opening ? GuardPatterns.isFinalSubmit(t) || t.toLowerCase(Locale.ROOT).contains("apply now") : GuardPatterns.isSubmitLike(t)) {
                    return Outcome.refused("refused: '" + t.strip() + "' looks like a submit or apply button");
                }
            }
            if (target.locator("xpath=ancestor::form[.//input[@type='password']]").count() > 0) {
                return Outcome.refused("refused: inside a login form");
            }
            String label = String.join(" ", own, value, aria, title);
            switch (purpose) {
                case OPEN_APPLICATION_FORM -> {
                    if (!GuardPatterns.APPLY_LABEL.matcher(own.isBlank() ? (value.isBlank() ? aria : value) : own).find()) {
                        return Outcome.refused("refused: not a link or button that opens an application");
                    }
                    if (target.locator("xpath=ancestor::form[.//input[not(@type='hidden') and not(@type='submit') and not(@type='button')]"
                            + " or .//select or .//textarea]").count() > 0) {
                        return Outcome.refused("refused: inside a form with fields, so it may send the form");
                    }
                    // On a page that already shows fields the application has begun: nothing opens another page from here.
                    if (PageScripts.detect(page).fieldCount() > 0) {
                        return Outcome.refused("refused: the page already is a form");
                    }
                }
                case ADVANCE_STEP -> {
                    String name = own.isBlank() ? (value.isBlank() ? aria : value) : own;
                    if (!GuardPatterns.isAdvance(name)) {
                        return Outcome.refused("refused: '" + name.strip() + "' is not a Next or Continue button");
                    }
                    String why = pageMaySend(target);
                    if (why != null) {
                        return Outcome.refused("refused: " + why);
                    }
                }
                case OPEN_DROPDOWN -> {
                    boolean list = role.equals("combobox") || !nz(target.getAttribute("aria-haspopup")).isBlank()
                            || target.getAttribute("aria-expanded") != null || !nz(target.getAttribute("aria-controls")).isBlank();
                    if (!list) {
                        return Outcome.refused("refused: not a drop-down");
                    }
                }
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
                    boolean option = role.equals("option") || target.locator("xpath=ancestor::*[@role='listbox']").count() > 0
                            || (listId != null && target.locator("xpath=ancestor::*[@id='" + listId + "']").count() > 0);
                    if (!option) {
                        return Outcome.refused("not a suggestion in a list");
                    }
                }
                case AKTIVITET_SPARA -> {
                    // exact text, exact page; the deny list above already refused anything submit-like
                    if (aktivitetFormUrl == null || !stripQueryAndHash(page.url()).equals(stripQueryAndHash(aktivitetFormUrl))) {
                        return Outcome.refused("refused: Spara is only clicked on the aktivitetsrapport form");
                    }
                    if (!SAVE_TEXT.equals(own.strip()) && !(own.isBlank() && SAVE_TEXT.equals(value.strip()))) {
                        return Outcome.refused("refused: the button does not read exactly Spara");
                    }
                }
            }
            target.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            return Outcome.ok();
        } catch (PlaywrightException e) {
            return Outcome.refused("could not click: " + first(e));
        }
    }

    // ---- from the ad to the application form, and from step to step ----

    /** What {@link #follow} did; {@code popup} is the new tab when the click opened one. */
    public record Opened(boolean done, String how, Page popup) {
        static Opened refused(String why) {
            return new Opened(false, why, null);
        }
    }

    private static final int NAVIGATION_TIMEOUT_MS = 20_000;
    private static final int POPUP_WAIT_MS = 1_500;
    private static final int MAX_CONTROLS_READ = 80;

    /**
     * Presses one link or button of the snapshot that only moves the application on: the Apply link of an ad (refused on a page that
     * already is a form and after anything was filled), or the Next / Continue / Apply manually / Continue as guest button of a form
     * (refused on a review page, next to a button that sends, and for any label that is not exactly such a word). A link with an
     * ordinary address is loaded after the address check; everything else is a click through {@link #click}.
     */
    public Opened follow(String ref, boolean anythingFilled, java.util.function.Predicate<String> addressAllowed) {
        if (ref == null || !PageSnapshot.SAFE_REF.matcher(ref).matches()) {
            return Opened.refused("refused: not an element of the page");
        }
        List<Page> popups = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<Page> listener = popups::add;
        page.onPopup(listener);
        try {
            Locator target = page.locator("aria-ref=" + ref);
            if (target.count() != 1) {
                return Opened.refused("the page changed while looking for the button");
            }
            String label = labelOf(target);
            boolean advances = GuardPatterns.isAdvance(label);
            boolean opens = GuardPatterns.APPLY_LABEL.matcher(label).find() && !advances;
            if (!advances && !opens) {
                return Opened.refused("refused: '" + label + "' does not open or continue an application");
            }
            String href = nz(target.getAttribute("href")).strip();
            String absolute = href.isEmpty() || href.startsWith("#") ? null : absolute(href);
            if (!href.isEmpty() && !href.startsWith("#") && (absolute == null || !(absolute.startsWith("http://") || absolute.startsWith("https://") || absolute.startsWith("file:")))) {
                return Opened.refused("refused: '" + label + "' is not a link to a web page");
            }
            if (opens) {
                if (anythingFilled) {
                    return Opened.refused("refused: something was already filled on this application");
                }
                if (PageScripts.detect(page).fieldCount() > 0) {
                    return Opened.refused("refused: the page already is a form");
                }
            }
            if (absolute != null && !addressAllowed.test(absolute)) {
                return Opened.refused("refused: " + hostLabel(absolute) + " is not an address this app opens");
            }
            if (absolute != null && opens) {
                try {
                    page.navigate(absolute, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
                    return new Opened(true, "loaded " + stripQueryAndHash(absolute), null);
                } catch (PlaywrightException e) {
                    return Opened.refused("could not open " + hostLabel(absolute) + ": " + first(e));
                }
            }
            Outcome outcome = click(target, advances ? ClickPurpose.ADVANCE_STEP : ClickPurpose.OPEN_APPLICATION_FORM);
            if (!outcome.done()) {
                return Opened.refused(outcome.reason());
            }
            page.waitForTimeout(POPUP_WAIT_MS); // a button that opens a new tab does so at once
            return new Opened(true, "clicked '" + label + "'", popups.isEmpty() ? null : popups.get(0));
        } catch (PlaywrightException e) {
            return Opened.refused("could not click: " + first(e));
        } finally {
            page.offPopup(listener);
        }
    }

    private String absolute(String href) {
        try {
            return java.net.URI.create(page.url()).resolve(href).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Why a Next or Continue button here might send the application, or null. It might when the page reads like the last step
     * (a review, summary or confirm heading or step name) or when another visible control on it says submit, send or finish.
     */
    private String pageMaySend(Locator self) {
        try {
            // Visible headings only: a multi-step form keeps the later steps in the page, hidden, and their text still reads back.
            Locator headings = page.locator("h1, h2, h3, [role=heading], legend, [aria-current=step], [aria-current=page]");
            int count = Math.min(headings.count(), MAX_CONTROLS_READ);
            for (int i = 0; i < count; i++) {
                Locator hd = headings.nth(i);
                if (!hd.isVisible()) {
                    continue;
                }
                String h = hd.innerText(new Locator.InnerTextOptions().setTimeout(1_000)).strip();
                if (!h.isEmpty() && h.length() <= 80 && GuardPatterns.REVIEW_PAGE.matcher(h).find()) {
                    return "this looks like the review step ('" + h + "'), and the next button may send the application";
                }
            }
            Locator controls = page.locator("button, input[type=submit], input[type=button], [role=button], a");
            int n = Math.min(controls.count(), MAX_CONTROLS_READ);
            for (int i = 0; i < n; i++) {
                Locator c = controls.nth(i);
                if (!c.isVisible()) {
                    continue;
                }
                String text = labelOf(c);
                if (GuardPatterns.sendsApplication(text) && !text.equals(labelOf(self))) {
                    return "the page has a button '" + text.strip() + "' that sends the application";
                }
            }
        } catch (PlaywrightException e) {
            return "the page could not be checked for a sending button";
        }
        return null;
    }

    /** Waits for the page to stop changing: load, a short network lull, then two equal snapshots in a row. Never throws. */
    public void awaitStable() {
        settle();
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3_000));
        } catch (PlaywrightException ignored) {
            // a page with endless background requests is read as it is
        }
        // A single-page app draws its buttons after the load event: three equal looks in a row (about a second) count as done.
        PageSnapshot last = null;
        int equal = 0;
        for (int i = 0; i < 14; i++) {
            try {
                PageSnapshot now = PageScripts.snapshot(page);
                equal = now.sameAs(last) ? equal + 1 : 0;
                if (equal >= 2) {
                    return;
                }
                last = now;
                page.waitForTimeout(450);
            } catch (PlaywrightException e) {
                return;
            }
        }
    }

    /** Waits (briefly, with a hard limit) for the page to finish loading. Never throws. */
    public void settle() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(8_000));
        } catch (PlaywrightException ignored) {
            // a page that never settles is read as it is
        }
    }

    private static String hostLabel(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "that address" : h;
        } catch (IllegalArgumentException e) {
            return "that address";
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
        if (PageSnapshot.SAFE_REF.matcher(f.id()).matches()) {
            return page.locator("aria-ref=" + f.id());
        }
        return frame(f).locator("[data-jp-id=\"" + f.id() + "\"]");
    }

    private Frame frame(FormField f) {
        List<Frame> frames = page.frames();
        return f.frame() < frames.size() ? frames.get(f.frame()) : page.mainFrame();
    }

    /** The visible text, or the raw text content for nodes (such as inputs) that have no innerText. */
    private static String safeText(Locator l) {
        try {
            return nz(l.innerText(new Locator.InnerTextOptions().setTimeout(1_500)));
        } catch (PlaywrightException e) {
            try {
                return nz(l.textContent(new Locator.TextContentOptions().setTimeout(1_000)));
            } catch (PlaywrightException again) {
                return "";
            }
        }
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
