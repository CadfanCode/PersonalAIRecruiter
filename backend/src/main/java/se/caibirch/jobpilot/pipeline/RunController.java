package se.caibirch.jobpilot.pipeline;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import se.caibirch.jobpilot.jobs.JobExport;
import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobModel.Status;
import se.caibirch.jobpilot.jobs.JobService;
import se.caibirch.jobpilot.pipeline.PipelineModel.Run;
import se.caibirch.jobpilot.pipeline.PipelineModel.RunView;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    public record StartRequest(String prompt, Integer maxAgeDays, Integer limit, Boolean includeWeb,
                               Boolean autoContinue) {
    }

    private final PipelineService pipeline;
    private final JobService jobs;

    public RunController(PipelineService pipeline, JobService jobs) {
        this.pipeline = pipeline;
        this.jobs = jobs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> start(@RequestBody StartRequest r) {
        long id = pipeline.start(r.prompt(), new RunOptions(r.maxAgeDays(), r.limit(), r.includeWeb(), r.autoContinue()));
        return Map.of("id", id);
    }

    @GetMapping
    public List<Run> list() {
        return pipeline.list();
    }

    @GetMapping("/{id}")
    public RunView get(@PathVariable long id) {
        return pipeline.view(id);
    }

    @PutMapping("/{id}/profile")
    public RunView editProfile(@PathVariable long id, @RequestBody JsonNode profile) {
        return pipeline.editProfile(id, profile);
    }

    @PostMapping("/{id}/continue")
    public RunView continueRun(@PathVariable long id) {
        return pipeline.continueRun(id);
    }

    @PostMapping("/{id}/cancel")
    public RunView cancel(@PathVariable long id) {
        return pipeline.cancel(id);
    }

    @GetMapping("/{id}/jobs")
    public List<Row> jobs(@PathVariable long id, @RequestParam(required = false) String q,
                          @RequestParam(required = false) Status status, @RequestParam(required = false) String language,
                          @RequestParam(required = false) Integer minScore, @RequestParam(required = false) String sort,
                          @RequestParam(required = false) Integer top) {
        return jobs.rowsOfRun(id, new JobService.Query(q, status, language, minScore, sort, top));
    }

    @GetMapping("/{id}/jobs.csv")
    public ResponseEntity<byte[]> csv(@PathVariable long id) {
        return download(JobExport.csv(all(id)), "text/csv; charset=UTF-8", "jobs-run-" + id + ".csv");
    }

    @GetMapping("/{id}/jobs.xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable long id) {
        return download(JobExport.xlsx(all(id)),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "jobs-run-" + id + ".xlsx");
    }

    private List<Row> all(long id) {
        return jobs.rowsOfRun(id, new JobService.Query(null, null, null, null, null, null));
    }

    private static ResponseEntity<byte[]> download(byte[] body, String type, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType(type)).body(body);
    }
}
