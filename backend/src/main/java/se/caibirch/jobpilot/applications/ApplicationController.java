package se.caibirch.jobpilot.applications;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import se.caibirch.jobpilot.applications.ApplicationModel.View;
import se.caibirch.jobpilot.documents.DocumentService;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    public record SelectRequest(List<Long> jobIds, Long runId) {
    }

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<View> list() {
        return service.list();
    }

    @PostMapping
    public List<View> select(@RequestBody SelectRequest r) {
        return service.select(r.jobIds(), r.runId());
    }

    /** Selects the jobs and starts one batch that writes a CV and a letter for each. Poll {@code GET /api/applications}. */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, List<DocumentService.BatchItem>> createDocuments(@RequestBody SelectRequest r) {
        return Map.of("items", service.createDocuments(r.jobIds(), r.runId()));
    }

    /** The jobs to act on; empty or missing means every selected application that is not submitted yet. */
    public record JobsRequest(List<Long> jobIds) {
    }

    /** "Approve all": per document, what was approved and what was refused and why. Never all-or-nothing. */
    @PostMapping("/approve-all")
    public List<ApplicationModel.ApprovalResult> approveAll(@RequestBody(required = false) JobsRequest r) {
        return service.approveAll(r == null ? null : r.jobIds());
    }

    /** "Apply": tabs open only for applications with both documents approved; the rest come back as skipped, with reasons. */
    @PostMapping("/apply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApplicationModel.ApplyResult apply(@RequestBody(required = false) JobsRequest r) {
        return service.applyApproved(r == null ? null : r.jobIds());
    }

    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long jobId) {
        service.remove(jobId);
    }

    /** The user submitted this application by hand and says so: records it for the activity report. */
    @PostMapping("/{jobId}/submitted")
    public View submitted(@PathVariable long jobId) {
        return service.markSubmitted(jobId);
    }

    @DeleteMapping("/{jobId}/submitted")
    public View undoSubmitted(@PathVariable long jobId) {
        return service.undoSubmitted(jobId);
    }
}
