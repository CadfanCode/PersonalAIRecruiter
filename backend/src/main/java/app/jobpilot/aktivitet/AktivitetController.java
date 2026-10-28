package app.jobpilot.aktivitet;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import app.jobpilot.aktivitet.AktivitetModel.Entry;
import app.jobpilot.aktivitet.AktivitetModel.LogRow;
import app.jobpilot.aktivitet.AktivitetModel.Plan;
import app.jobpilot.aktivitet.AktivitetModel.Reminder;

@RestController
@RequestMapping("/api/aktivitet")
public class AktivitetController {

    public record LogRequest(List<Entry> entries) {
    }

    private final AktivitetService service;
    private final AktivitetLogRepository logs;
    private final ReminderService reminder;

    public AktivitetController(AktivitetService service, AktivitetLogRepository logs, ReminderService reminder) {
        this.service = service;
        this.logs = logs;
        this.reminder = reminder;
    }

    /** What would be written for these jobs (or why not), for the confirmation step. Nothing is saved. */
    @GetMapping("/plan")
    public List<Plan> plan(@RequestParam List<Long> jobIds) {
        return service.plan(jobIds);
    }

    /** Saves exactly the entries the user confirmed. Returns at once; poll {@code /api/runs/{runId}}. */
    @PostMapping("/log")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Long> log(@RequestBody LogRequest request) {
        return Map.of("runId", service.start(request.entries()));
    }

    @GetMapping("/log")
    public List<LogRow> log() {
        return logs.all();
    }

    @GetMapping("/reminder")
    public Reminder reminder() {
        return reminder.reminder();
    }
}
