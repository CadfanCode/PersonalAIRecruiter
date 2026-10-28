package app.jobpilot.jobs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import app.jobpilot.jobs.JobModel.Detail;
import app.jobpilot.jobs.JobModel.Status;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record Patch(Status status, String notes) {
    }

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    /** {@code run} selects whose score to show: the score depends on the run's search profile. */
    @GetMapping("/{id}")
    public Detail detail(@PathVariable long id, @RequestParam(required = false) Long run) {
        return service.detail(id, run);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patch(@PathVariable long id, @RequestBody Patch patch) {
        service.patch(id, patch.status(), patch.notes());
    }
}
