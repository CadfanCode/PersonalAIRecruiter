package se.caibirch.jobpilot.applications;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.applications.ApplicationModel.DocState;
import se.caibirch.jobpilot.applications.ApplicationModel.Generation;
import se.caibirch.jobpilot.applications.ApplicationModel.View;
import se.caibirch.jobpilot.apply.ApplyService;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.documents.DocumentModel.Kind;
import se.caibirch.jobpilot.documents.DocumentRepository;
import se.caibirch.jobpilot.documents.DocumentService;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.jobs.JobModel.Status;
import se.caibirch.jobpilot.pipeline.RunRepository;

/**
 * The pipeline basket (Results, Documents, Finalize Applications). Nothing here submits anything: an application becomes
 * "submitted" only when the user says so, after submitting it by hand (hard rule 1).
 */
@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final JobRepository jobs;
    private final RunRepository runs;
    private final DocumentRepository documents;
    private final DocumentService documentService;
    private final ApplyService apply;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, JobRepository jobs, RunRepository runs,
                              DocumentRepository documents, DocumentService documentService, ApplyService apply,
                              ObjectMapper mapper, Clock clock) {
        this.applications = applications;
        this.jobs = jobs;
        this.runs = runs;
        this.documents = documents;
        this.documentService = documentService;
        this.apply = apply;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<View> list() {
        return applications.all().stream().map(this::view).toList();
    }

    /** Adds the jobs to the basket. The mode and role type come from the run the jobs were found in. */
    public List<View> select(List<Long> jobIds, Long runId) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new IllegalArgumentException("jobIds is required");
        }
        for (long id : jobIds.stream().distinct().toList()) {
            jobs.findRow(id, null).orElseThrow(() -> new NotFoundException("No job " + id));
        }
        String mode = runId == null ? "TARGETED" : runs.mode(runId).orElseThrow(() -> new NotFoundException("No run " + runId));
        for (long id : jobIds.stream().distinct().toList()) {
            applications.select(id, runId, mode, roleTypeOf(id, runId));
        }
        return jobIds.stream().distinct().map(id -> view(applications.find(id).orElseThrow())).toList();
    }

    /** Selects the jobs and generates a tailored CV and cover letter for each, as one batch. */
    public List<DocumentService.BatchItem> createDocuments(List<Long> jobIds, Long runId) {
        select(jobIds, runId);
        return documentService.startBatch(jobIds, runId);
    }

    public void remove(long jobId) {
        if (!applications.remove(jobId)) {
            throw new NotFoundException("Job " + jobId + " is not selected");
        }
    }

    /** The user's own confirmation that they submitted the application by hand. Idempotent. */
    public View markSubmitted(long jobId) {
        if (applications.find(jobId).isEmpty()) {
            select(List.of(jobId), null);
        }
        var before = applications.find(jobId).orElseThrow();
        if (before.submittedAt() == null) {
            applications.markSubmitted(jobId, clock.instant(), docsUsed(jobId));
        }
        if (!"LOGGED".equals(before.jobStatus())) {
            jobs.patch(jobId, Status.APPLIED, null);
        }
        apply.finish(jobId);
        return view(applications.find(jobId).orElseThrow());
    }

    public View undoSubmitted(long jobId) {
        applications.find(jobId).orElseThrow(() -> new NotFoundException("Job " + jobId + " is not selected"));
        applications.clearSubmitted(jobId);
        return view(applications.find(jobId).orElseThrow());
    }

    /** Role type of the run's job: set by Discover runs (milestone 7); null for targeted and manual ones. */
    private String roleTypeOf(long jobId, Long runId) {
        return null;
    }

    private JsonNode docsUsed(long jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            documents.find(jobId, kind).ifPresent(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.id());
                m.put("version", d.version());
                m.put("approved", d.approved());
                m.put("file", d.pdfPath() == null ? null : Path.of(d.pdfPath()).getFileName().toString());
                out.put(kind == Kind.CV ? "cv" : "letter", m);
            });
        }
        return mapper.valueToTree(out);
    }

    private View view(ApplicationRepository.Row r) {
        DocState cv = documents.find(r.jobId(), Kind.CV).map(d -> new DocState(d.id(), d.version(), d.approved())).orElse(null);
        DocState letter = documents.find(r.jobId(), Kind.LETTER).map(d -> new DocState(d.id(), d.version(), d.approved())).orElse(null);
        Generation generation = runs.latestDocumentsRun(r.jobId())
                .map(run -> new Generation(run.id(), run.status().name(), run.step(), run.progress(), run.message())).orElse(null);
        JsonNode submittedDocs = null;
        if (r.docsJson() != null) {
            try {
                submittedDocs = mapper.readTree(r.docsJson());
            } catch (JsonProcessingException e) {
                submittedDocs = null;
            }
        }
        return new View(r.jobId(), r.title(), r.employer(), r.municipality(), r.url(), r.applyUrl(), r.searchMode(),
                r.roleType(), r.score(), r.jobStatus(), r.selectedAt(), r.submittedAt(), cv, letter, generation, submittedDocs);
    }
}
