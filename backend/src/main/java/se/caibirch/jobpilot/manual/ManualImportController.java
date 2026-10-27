package se.caibirch.jobpilot.manual;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/import")
public class ManualImportController {

    public record ImportRequest(String url, String text) {
    }

    public record TextRequest(String text) {
    }

    private final ManualImportService service;

    public ManualImportController(ManualImportService service) {
        this.service = service;
    }

    /** Adds one job by its address (and optionally its pasted text). Returns the run to follow at {@code /api/runs/{id}}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> start(@RequestBody ImportRequest request) {
        return Map.of("id", service.start(request.url(), request.text()));
    }

    /** The ad text for a run that is waiting for it (sites that cannot be read automatically). */
    @PostMapping("/{runId}/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void text(@PathVariable long runId, @RequestBody TextRequest request) {
        service.provideText(runId, request.text());
    }
}
