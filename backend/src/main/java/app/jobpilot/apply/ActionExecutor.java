package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.microsoft.playwright.Page;

import app.jobpilot.apply.ActionValidator.Planned;
import app.jobpilot.apply.ActionValidator.Skipped;
import app.jobpilot.apply.BrowserGuard.Outcome;

/**
 * Runs validated actions through the {@link BrowserGuard}, then reads the page again to prove each value landed:
 * controlled inputs can reject a fill, and a page that re-renders drops its fields (SPEC 9.2).
 */
public final class ActionExecutor {

    public record Done(String fieldId, String label, String action, String detail) {
    }

    public record Result(List<Done> done, List<Skipped> skipped) {
    }

    private final Page page;
    private final BrowserGuard guard;

    public ActionExecutor(Page page, BrowserGuard guard) {
        this.page = page;
        this.guard = guard;
    }

    public Result execute(List<Planned> planned) {
        List<Done> done = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Planned> ran = new ArrayList<>();
        for (Planned p : planned) {
            Outcome o = switch (p.action()) {
                case "fill" -> guard.fill(p.field(), p.value());
                case "select", "choose" -> guard.choose(p.field(), p.value());
                case "check" -> guard.check(p.field(), true);
                case "uncheck" -> guard.check(p.field(), false);
                case "upload" -> guard.upload(p.field(), p.file());
                default -> new Outcome(false, "unknown action " + p.action());
            };
            if (o.done()) {
                ran.add(p);
            } else {
                skipped.add(new Skipped(p.field().id(), p.field().questionText(), o.reason()));
            }
        }
        PageSnapshot after = ran.isEmpty() ? null : PageScripts.snapshot(page);
        // Tagged fields (jp-N) are read back by the extractor, snapshot refs by the snapshot.
        List<FormField> tagged = ran.stream().anyMatch(p -> isTagged(p.field())) ? PageScripts.extract(page) : List.of();
        for (Planned p : ran) {
            FormField now = isTagged(p.field()) ? tagged.stream().filter(t -> t.id().equals(p.field().id())).findFirst().orElse(null)
                    : find(after, p.field());
            String problem = verify(p, now);
            if (problem == null) {
                done.add(new Done(p.field().id(), p.field().questionText(), p.action(),
                        p.action().equals("upload") ? p.file().getFileName().toString() : p.value()));
            } else {
                skipped.add(new Skipped(p.field().id(), p.field().questionText(), problem));
            }
        }
        return new Result(done, skipped);
    }

    private static boolean isTagged(FormField f) {
        return f.id().matches("^(f\\d+-)?jp-\\d+$");
    }

    /** The field again after the actions: by its ref, or, when the page rebuilt it, the one control with the same role and name. */
    private static FormField find(PageSnapshot snapshot, FormField f) {
        FormField byRef = snapshot.field(f.id());
        if (byRef != null) {
            return byRef;
        }
        List<FormField> same = snapshot.fields().stream().filter(o -> o.type().equals(f.type())
                && o.label().equals(f.label()) && o.legend().equals(f.legend()) && o.optionLabel().equals(f.optionLabel())).toList();
        return same.size() == 1 ? same.get(0) : null;
    }

    /** Masked inputs reformat what is typed (spaces in a phone number, a date picker); only letters and digits are compared. */
    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    /** Null when the value is on the page; otherwise why the fill did not stick. */
    static String verify(Planned p, FormField now) {
        if (p.action().equals("upload") || p.action().equals("choose") || p.action().equals("select")) {
            return null; // an upload cannot be read back, and a custom drop-down shows its choice in ways that differ per site
        }
        if (now == null) {
            return "the page changed and the field disappeared";
        }
        return switch (p.action()) {
            case "fill" -> squash(now.value()).equals(squash(p.value())) ? null : "the page did not keep the value";
            case "check" -> now.checked() ? null : "the page did not keep the tick";
            case "uncheck" -> !now.checked() ? null : "the page did not keep the untick";
            default -> null;
        };
    }
}
