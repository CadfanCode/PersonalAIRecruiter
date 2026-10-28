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
 * Allowed: fill, select, check, upload on fields found by the extractor, going to the application form from an ad page, and a
 * click for a few named purposes: the necessary-only cookie button, a suggestion the executor itself just opened, and the link
 * or button that opens the application form (never one that sends it). Everything else is refused here, in code. No other class may call {@code click}, {@code press}, {@code evaluate} and similar on a
 * page (a source-scan test fails the build if one does). Enter is never pressed because it can submit a form.
 */
public final class BrowserGuard {

    /**
     * Why a click is allowed. AKTIVITET_SPARA is the Spara button of the aktivitetsrapport form. OPEN_APPLICATION_FORM is the
     * "Ansök" or "Apply" link or button of an ad page that leads to the form: it is refused on a page that already is a form,
     * after anything was filled, for a submit button, inside a form that has inputs, and for any label with a final-submit word.
     */
    public enum ClickPurpose { COOKIE_NECESSARY, AUTOCOMPLETE_OPTION, AKTIVITET_SPARA, OPEN_APPLICATION_FORM }

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
        Frame frame = frame(f);
        String listId = listIdOf(input);
        // Only visible items count: a closed list from another field must not look like this field's suggestions.
        Locator options = frame.locator(listId == null ? "[role=option], [role=listbox] li"
                : "[role=option], [role=listbox] li, [id=\"" + listId + "\"] li")
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
        return value == null ? "" : value.strip();
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
            if (type.equals("submit") && purpose != ClickPurpose.AKTIVITET_SPARA) {
                return Outcome.refused("refused: this is a submit button");
            }
            // A <button> without a type inside a <form> is a submit button by default.
            if (purpose != ClickPurpose.AKTIVITET_SPARA && target.locator("xpath=self::button[not(@type)]/ancestor::form").count() > 0) {
                return Outcome.refused("refused: a button inside a form, which would submit it");
            }
            boolean opening = purpose == ClickPurpose.OPEN_APPLICATION_FORM;
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

    // ---- from the ad to the application form ----

    /** What {@link #openApplication} did; {@code popup} is the new tab when the click opened one. */
    public record Opened(boolean done, String how, Page popup) {
        static Opened refused(String why) {
            return new Opened(false, why, null);
        }
    }

    private static final int NAVIGATION_TIMEOUT_MS = 20_000;
    private static final int POPUP_WAIT_MS = 1_500;
    private static final String CANDIDATE_SELECTOR = "button, a, [role=button], input[type=button], input[type=submit]";

    /**
     * Goes from an ad to its application form. A link with an address is simply loaded (no click); a button or a link without
     * an address is clicked with {@link ClickPurpose#OPEN_APPLICATION_FORM}. Refused when the page already is a form, when
     * JobPilot has already filled something, when the candidate is not an apply link, or when the address is not an allowed one.
     */
    public Opened openApplication(ApplyCandidates.Candidate c, boolean pageIsForm, boolean anythingFilled,
                                  java.util.function.Predicate<String> addressAllowed) {
        if (pageIsForm) {
            return Opened.refused("refused: the page already is a form");
        }
        if (anythingFilled) {
            return Opened.refused("refused: something was already filled on this application");
        }
        if (!ApplyCandidates.eligible(c)) {
            return Opened.refused("refused: '" + c.text() + "' is not a link that opens an application");
        }
        if (c.anchorWithAddress()) {
            String url = c.href();
            if (!addressAllowed.test(url)) {
                return Opened.refused("refused: " + hostLabel(url) + " is not an address this app opens");
            }
            try {
                page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
                return new Opened(true, "loaded " + stripQueryAndHash(url), null);
            } catch (PlaywrightException e) {
                return Opened.refused("could not open " + hostLabel(url) + ": " + first(e));
            }
        }
        List<Page> popups = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<Page> listener = popups::add;
        page.onPopup(listener);
        try {
            Locator target = page.locator(CANDIDATE_SELECTOR).nth(c.index());
            String now = safeText(target);
            if (!now.startsWith(c.text().length() > 20 ? c.text().substring(0, 20) : c.text())) {
                return Opened.refused("the page changed while looking for the apply button");
            }
            Outcome outcome = click(target, ClickPurpose.OPEN_APPLICATION_FORM);
            if (!outcome.done()) {
                return Opened.refused(outcome.reason());
            }
            page.waitForTimeout(POPUP_WAIT_MS); // a button that opens a new tab does so at once
            return new Opened(true, "clicked '" + c.text() + "'", popups.isEmpty() ? null : popups.get(0));
        } catch (PlaywrightException e) {
            return Opened.refused("could not click: " + first(e));
        } finally {
            page.offPopup(listener);
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
