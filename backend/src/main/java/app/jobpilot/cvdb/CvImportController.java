package app.jobpilot.cvdb;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cv/import")
public class CvImportController {

    private final CvImportService service;

    public CvImportController(CvImportService service) {
        this.service = service;
    }

    /** Multipart upload of PDF, DOCX, TXT or MD files. Reading starts in the background; nothing is saved until the commit call. */
    @PostMapping
    public CvImportService.ImportJob start(@RequestParam("files") List<MultipartFile> files) throws IOException {
        List<CvImportService.UploadedFile> uploaded = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            uploaded.add(new CvImportService.UploadedFile(f.getOriginalFilename(), f.getBytes()));
        }
        return service.start(uploaded);
    }

    /** The reading with this run id: RUNNING, DONE (with the preview) or FAILED. */
    @org.springframework.web.bind.annotation.GetMapping("/{runId:\\d+}")
    public CvImportService.ImportJob job(@PathVariable long runId) {
        return service.job(runId);
    }

    /** The newest reading that is still waiting for the user, or 204. Lets the UI find it again after the user came back. */
    @org.springframework.web.bind.annotation.GetMapping("/current")
    public org.springframework.http.ResponseEntity<CvImportService.ImportJob> current() {
        return service.current().map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{runId:\\d+}")
    public void discard(@PathVariable long runId) {
        service.discard(runId);
    }

    @PostMapping("/{previewId}/commit")
    public CvImportService.CommitResult commit(@PathVariable String previewId,
                                                @RequestBody CvImportService.CommitRequest request) {
        return service.commit(previewId, request);
    }
}
