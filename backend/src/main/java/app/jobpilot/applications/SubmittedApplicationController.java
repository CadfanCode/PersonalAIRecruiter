package app.jobpilot.applications;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.jobpilot.applications.SubmittedApplicationRepository.Detail;
import app.jobpilot.applications.SubmittedApplicationRepository.Summary;
import app.jobpilot.common.ApiExceptions.NotFoundException;

/** Submitted Applications: what the user sent, exactly as it was sent, and whether it was reported to Arbetsförmedlingen. */
@RestController
@RequestMapping("/api/submitted")
public class SubmittedApplicationController {

    private final SubmittedApplicationRepository repo;

    public SubmittedApplicationController(SubmittedApplicationRepository repo) {
        this.repo = repo;
    }

    /** @param reported omitted: all; false: only those not yet reported; true: only the reported ones */
    @GetMapping
    public List<Summary> list(@RequestParam(required = false) Boolean reported) {
        return repo.all().stream().filter(s -> reported == null || (s.reportedAt() != null) == reported).toList();
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable long id) {
        return repo.detail(id).orElseThrow(() -> new NotFoundException("No submitted application " + id));
    }

    @GetMapping("/{id}/cv.pdf")
    public ResponseEntity<byte[]> cv(@PathVariable long id) {
        return pdf(id, "cv");
    }

    @GetMapping("/{id}/letter.pdf")
    public ResponseEntity<byte[]> letter(@PathVariable long id) {
        return pdf(id, "letter");
    }

    private ResponseEntity<byte[]> pdf(long id, String kind) {
        Summary s = repo.find(id).orElseThrow(() -> new NotFoundException("No submitted application " + id));
        byte[] bytes = repo.pdf(id, kind).orElseThrow(() -> new NotFoundException("That application had no " + (kind.equals("cv") ? "CV" : "cover letter")));
        String name = kind.equals("cv") ? s.cvFile() : s.letterFile();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name == null ? kind + ".pdf" : name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }
}
