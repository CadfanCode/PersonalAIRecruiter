package app.jobpilot.apply;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apply")
public class ApplyController {

    public record OpenRequest(List<Long> jobIds) {
    }

    public record AnswersRequest(List<ApplyService.AnswerInput> answers) {
    }

    private final ApplyService service;

    public ApplyController(ApplyService service) {
        this.service = service;
    }

    /** Opens one tab per job (at most 5) and starts filling. Returns at once; poll {@code /api/apply/status}. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<TabState.View> open(@RequestBody OpenRequest request) {
        return service.open(request.jobIds());
    }

    /** "Run all": opens every job, at most the tab limit at once; the rest are queued and start by themselves. */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<TabState.View> openBatch(@RequestBody OpenRequest request) {
        return service.openBatch(request.jobIds());
    }

    @GetMapping("/status")
    public List<TabState.View> status() {
        return service.status();
    }

    /** "Fill this page": re-scan and fill the page the tab is on now. {@code restart} reloads the application first. */
    @PostMapping("/{jobId}/fill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TabState.View fill(@PathVariable long jobId, @RequestParam(defaultValue = "false") boolean restart) {
        return service.fill(jobId, restart);
    }

    @PostMapping("/{jobId}/answers")
    public TabState.View answers(@PathVariable long jobId, @RequestBody AnswersRequest request) {
        return service.saveAnswers(jobId, request.answers());
    }

    @PostMapping("/{jobId}/focus")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void showTab(@PathVariable long jobId) {
        service.showTab(jobId);
    }

    @PostMapping("/{jobId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable long jobId) {
        service.close(jobId);
    }
}
