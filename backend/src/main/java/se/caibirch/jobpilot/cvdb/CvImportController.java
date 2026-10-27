package se.caibirch.jobpilot.cvdb;

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

    /** Multipart upload of PDF, DOCX, TXT or MD files. Nothing is saved until the commit call. */
    @PostMapping
    public CvImportService.Preview preview(@RequestParam("files") List<MultipartFile> files) throws IOException {
        List<CvImportService.UploadedFile> uploaded = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            uploaded.add(new CvImportService.UploadedFile(f.getOriginalFilename(), f.getBytes()));
        }
        return service.preview(uploaded);
    }

    @PostMapping("/{previewId}/commit")
    public CvImportService.CommitResult commit(@PathVariable String previewId,
                                                @RequestBody CvImportService.CommitRequest request) {
        return service.commit(previewId, request);
    }
}
