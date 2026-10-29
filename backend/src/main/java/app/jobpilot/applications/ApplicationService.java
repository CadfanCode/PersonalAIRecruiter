package app.jobpilot.applications;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import app.jobpilot.applications.ApplicationModel.ApplyResult;
import app.jobpilot.applications.ApplicationModel.ApprovalResult;
import app.jobpilot.applications.ApplicationModel.DocState;
import app.jobpilot.applications.ApplicationModel.Generation;
import app.jobpilot.applications.ApplicationModel.Skipped;
import app.jobpilot.applications.ApplicationModel.View;
import app.jobpilot.apply.ApplyService;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.documents.DocumentModel.Kind;
import app.jobpilot.documents.DocumentRepository;
import app.jobpilot.documents.DocumentService;
import app.jobpilot.jobs.JobRepository;
import app.jobpilot.jobs.JobModel.Status;
import app.jobpilot.pipeline.RetentionService;
import app.jobpilot.pipeline.RunRepository;

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
    private final SubmittedApplicationRepository submitted;
    private final RetentionService retention;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, JobRepository jobs, RunRepository runs,
                              DocumentRepository documents, DocumentService documentService, ApplyService apply,
                              SubmittedApplicationRepository submitted, RetentionService retention, ObjectMapper mapper, Clock clock) {
        this.submitted = submitted;
        this.retention = retention;
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

    /**
     * Deletes these applications completely: the selection, both documents and their PDF files, the form-filling state and the
     * ad itself. Nothing is left behind. Submitted applications are never deleted here (they are the user's record), and nothing
     * is deleted at all if one of the named applications is submitted or is still being written.
     */
    public int delete(List<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new IllegalArgumentException("jobIds is required");
        }
        List<Long> ids = jobIds.stream().distinct().toList();
        for (long id : ids) {
            var row = applications.find(id).orElseThrow(() -> new NotFoundException("Job " + id + " is not selected"));
            if (row.submittedAt() != null) {
                throw new app.jobpilot.common.ApiExceptions.ConflictException(
                        "“" + row.title() + "” is submitted, so it stays in Submitted applications");
            }
            if (runs.activeDocumentsRun(id).isPresent()) {
                throw new app.jobpilot.common.ApiExceptions.ConflictException(
                        "The documents for “" + row.title() + "” are still being written. Delete it when they are done");
            }
        }
        ids.forEach(apply::discard);
        return retention.deleteJobs(ids);
    }

    /** The user's own confirmation that they submitted the application by hand. Idempotent. */
    public View markSubmitted(long jobId) {
        if (applications.find(jobId).isEmpty()) {
            select(List.of(jobId), null);
        }
        var before = applications.find(jobId).orElseThrow();
        if (before.submittedAt() == null) {
            Instant now = clock.instant();
            applications.markSubmitted(jobId, now, docsUsed(jobId));
            snapshot(before, now);
        }
        if (!"LOGGED".equals(before.jobStatus())) {
            jobs.patch(jobId, Status.APPLIED, null);
        }
        apply.finish(jobId);
        return view(applications.find(jobId).orElseThrow());
    }

    public View undoSubmitted(long jobId) {
        applications.find(jobId).orElseThrow(() -> new NotFoundException("Job " + jobId + " is not selected"));
        if (submitted.findByJob(jobId).map(s -> s.reportedAt() != null).orElse(false)) {
            throw new app.jobpilot.common.ApiExceptions.ConflictException(
                    "This application is already reported in the aktivitetsrapport, so it cannot be un-submitted");
        }
        applications.clearSubmitted(jobId);
        submitted.deleteForJob(jobId);
        return view(applications.find(jobId).orElseThrow());
    }

    /**
     * Freezes what is being submitted: the ad text, and the exact content and PDF bytes of the CV and letter as they are right now.
     * Later regeneration, chat edits and undo never change this record.
     */
    private void snapshot(ApplicationRepository.Row app, Instant now) {
        long jobId = app.jobId();
        String adText = jobs.adText(jobId).map(a -> a.description()).orElse(null);
        String language = jobs.adText(jobId).map(a -> a.language()).orElse(null);
        var cv = documents.find(jobId, Kind.CV);
        var letter = documents.find(jobId, Kind.LETTER);
        submitted.insert(new SubmittedApplicationRepository.NewSnapshot(jobId, app.title(), app.employer(), app.municipality(),
                app.url(), app.applyUrl(), language, adText, app.searchMode(), app.roleType(), app.score(), now,
                cv.map(DocumentRepository.Row::version).orElse(null), cv.map(d -> fileName(d.pdfPath())).orElse(null),
                cv.map(DocumentRepository.Row::contentJson).orElse(null), cv.map(d -> documentService.pdf(d.id())).orElse(null),
                letter.map(DocumentRepository.Row::version).orElse(null), letter.map(d -> fileName(d.pdfPath())).orElse(null),
                letter.map(DocumentRepository.Row::contentJson).orElse(null), letter.map(d -> documentService.pdf(d.id())).orElse(null)));
    }

    private static String fileName(String path) {
        return path == null ? null : Path.of(path).getFileName().toString();
    }

    /** The role the ad was found for in a Discover run (the one it was scored and is tailored against); null otherwise. */
    private String roleTypeOf(long jobId, Long runId) {
        return runId == null ? null : jobs.roleTitle(runId, jobId).orElse(null);
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
        List<String> blockers = blockers(r, cv, letter, generation);
        return new View(r.jobId(), r.runId(), r.title(), r.employer(), r.municipality(), r.url(), r.applyUrl(), r.searchMode(),
                r.roleType(), r.score(), r.jobStatus(), r.selectedAt(), r.submittedAt(), cv, letter, generation, submittedDocs,
                blockers.isEmpty() && r.submittedAt() == null, blockers, r.sentToApplyAt() != null);
    }

    /** Why an application cannot go on to the Apply stage; empty when both documents exist and are approved. */
    private static List<String> blockers(ApplicationRepository.Row r, DocState cv, DocState letter, Generation generation) {
        if (r.submittedAt() != null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        boolean writing = generation != null && ("QUEUED".equals(generation.status()) || "RUNNING".equals(generation.status()));
        if (writing) {
            out.add("The documents are still being written");
        }
        if (cv == null) {
            out.add(writing ? "No CV yet" : "No CV yet: create the documents first");
        } else if (!cv.approved()) {
            out.add("The CV is not approved");
        }
        if (letter == null) {
            out.add(writing ? "No cover letter yet" : "No cover letter yet: create the documents first");
        } else if (!letter.approved()) {
            out.add("The cover letter is not approved");
        }
        return out;
    }

    /**
     * "Approve all": tries to approve every document of the given applications (default: all that are not submitted yet).
     * A document with problems is refused with its reason and the rest are still approved; nothing is all-or-nothing.
     */
    public List<ApprovalResult> approveAll(List<Long> jobIds) {
        List<ApprovalResult> out = new java.util.ArrayList<>();
        for (ApplicationRepository.Row r : pending(jobIds)) {
            for (Kind kind : Kind.values()) {
                documents.find(r.jobId(), kind).ifPresent(d -> {
                    String label = kind == Kind.CV ? "cv" : "letter";
                    if (d.approved()) {
                        out.add(new ApprovalResult(r.jobId(), r.title(), label, true, true, null));
                        return;
                    }
                    try {
                        documentService.approve(d.id());
                        out.add(new ApprovalResult(r.jobId(), r.title(), label, true, false, null));
                    } catch (DocumentService.ApprovalRefused e) {
                        out.add(new ApprovalResult(r.jobId(), r.title(), label, false, false, summary(e.report())));
                    }
                });
            }
        }
        return out;
    }

    private static String summary(app.jobpilot.documents.DocumentModel.Report report) {
        List<String> fails = report.violations().stream()
                .filter(v -> v.severity() == app.jobpilot.documents.DocumentModel.Severity.FAIL).map(v -> v.message()).toList();
        return fails.isEmpty() ? "It still has problems to fix" : (fails.size() == 1 ? fails.get(0) : fails.get(0) + " (and " + (fails.size() - 1) + " more)");
    }

    /**
     * "Apply": opens a tab for every named application (the user's ticks in Documents) whose CV and cover letter are BOTH
     * approved and fills it in (never submitting). Those are marked as sent to the Apply stage and stay there. The others do
     * not proceed and come back with the reason, so the user sees which ones are left out. Without names it acts on the ones
     * already sent to Apply (the "Open and fill" button there); an application nobody ticked is never opened.
     */
    public ApplyResult applyApproved(List<Long> jobIds) {
        List<Long> ready = new java.util.ArrayList<>();
        List<Skipped> skipped = new java.util.ArrayList<>();
        List<ApplicationRepository.Row> chosen = pending(jobIds);
        if (jobIds == null || jobIds.isEmpty()) {
            chosen = chosen.stream().filter(r -> r.sentToApplyAt() != null).toList();
        }
        for (ApplicationRepository.Row r : chosen) {
            View v = view(r);
            if (v.readyToApply()) {
                ready.add(r.jobId());
            } else {
                skipped.add(new Skipped(r.jobId(), r.title(), String.join("; ", v.blockers())));
            }
        }
        applications.sendToApply(ready, clock.instant());
        List<app.jobpilot.apply.TabState.View> started = ready.isEmpty() ? List.of() : apply.openBatch(ready);
        return new ApplyResult(started, skipped);
    }

    /** Takes an application back out of the Apply stage (it stays selected, with its documents, in Documents). */
    public void withdrawFromApply(long jobId) {
        applications.find(jobId).orElseThrow(() -> new NotFoundException("Job " + jobId + " is not selected"));
        applications.withdrawFromApply(jobId);
    }

    private List<ApplicationRepository.Row> pending(List<Long> jobIds) {
        List<ApplicationRepository.Row> rows = applications.all().stream().filter(r -> r.submittedAt() == null).toList();
        if (jobIds == null || jobIds.isEmpty()) {
            return rows;
        }
        for (long id : jobIds) {
            if (applications.find(id).isEmpty()) {
                throw new NotFoundException("Job " + id + " is not selected");
            }
        }
        return rows.stream().filter(r -> jobIds.contains(r.jobId())).toList();
    }
}
