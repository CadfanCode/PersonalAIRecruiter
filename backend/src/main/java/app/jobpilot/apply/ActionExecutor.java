package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.microsoft.playwright.Page;

import app.jobpilot.apply.ActionValidator.Planned;
import app.jobpilot.apply.ActionValidator.Skipped;
import app.jobpilot.apply.BrowserGuard.Outcome;

/**
 * Runs validated actions through the {@link BrowserGuard}, then reads the page again to prove each value landed:
 * controlled inputs can reject a fill, and a page that re-renders drops its fields (SPEC 9.2 steps 5 and 6).
 */
public final class ActionExecutor {

    public record Done(String fieldId, String label, String action, String detail) {
    }

    /** @param newFields fields that appeared during the run (for example Ponty's category selects after an upload) */
    public record Result(List<Done> done, List<Skipped> skipped, List<FormField> newFields) {
    }

    private final Page page;
    private final BrowserGuard guard;

    public ActionExecutor(Page page, BrowserGuard guard) {
        this.page = page;
        this.guard = guard;
    }

    public Result execute(List<Planned> planned, List<FormField> before) {
        List<Done> done = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Planned> ran = new ArrayList<>();
        for (Planned p : planned) {
            Outcome o = switch (p.action()) {
                case "fill" -> guard.fill(p.field(), p.value());
                case "select" -> guard.select(p.field(), p.value());
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

        List<FormField> after = PageScripts.extract(page);
        Map<String, FormField> now = new HashMap<>();
        after.forEach(f -> now.put(f.id(), f));
        for (Planned p : ran) {
            String problem = verify(p, now.get(p.field().id()));
            if (problem == null) {
                done.add(new Done(p.field().id(), p.field().questionText(), p.action(),
                        p.action().equals("upload") ? p.file().getFileName().toString() : p.value()));
            } else {
                skipped.add(new Skipped(p.field().id(), p.field().questionText(), problem));
            }
        }
        Set<String> known = new HashSet<>();
        before.forEach(f -> known.add(f.id()));
        List<FormField> added = after.stream().filter(f -> !known.contains(f.id())).toList();
        return new Result(done, skipped, added);
    }

    /** Null when the value is on the page; otherwise why the fill did not stick. */
    static String verify(Planned p, FormField now) {
        if (now == null) {
            return "the page changed and the field disappeared";
        }
        return switch (p.action()) {
            case "fill" -> p.field().autocomplete() || now.value().strip().equals(p.value().strip()) ? null : "the page did not keep the value";
            case "select" -> selectedMatches(p, now) ? null : "the page did not keep the choice";
            case "check" -> now.checked() ? null : "the page did not keep the tick";
            case "uncheck" -> !now.checked() ? null : "the page did not keep the untick";
            default -> null; // uploads cannot be read back reliably; the guard confirmed the call
        };
    }

    private static boolean selectedMatches(Planned p, FormField now) {
        String v = p.value().strip();
        return now.options() != null && now.options().stream().anyMatch(o ->
                (o.value().equalsIgnoreCase(v) || o.label().equalsIgnoreCase(v)) && o.value().equals(now.value()));
    }
}
