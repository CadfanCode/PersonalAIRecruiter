package se.caibirch.jobpilot.documents;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Fact;
import se.caibirch.jobpilot.cvdb.CvService;
import se.caibirch.jobpilot.documents.DocumentModel.Kind;
import se.caibirch.jobpilot.documents.DocumentModel.Report;
import se.caibirch.jobpilot.jobs.JobRepository;

@RestController
@RequestMapping("/api")
public class DocumentController {

    public record GenerateRequest(Long runId) {
    }

    public record ChatRequest(String message, JsonNode content, List<DocumentChatService.Turn> history) {
    }

    public record ChatResponse(String reply, boolean changed, boolean approvalRevoked, View document) {
    }

    public record SelectionRequest(List<Long> pinned, List<Long> excluded) {
    }

    /** A fact as shown on hover next to the bullet that cites it. */
    public record FactView(long id, long entryId, String entryTitle, String textEn, String textSv) {
    }

    public record View(long id, long jobId, Kind kind, String language, int version, boolean approved, JsonNode content,
                       JsonNode report, Map<Long, FactView> facts, String updatedAt) {
    }

    public record JobDocuments(View cv, View letter, JsonNode selection, Set<Long> pinned, Set<Long> excluded) {
    }

    private final DocumentService service;
    private final DocumentChatService chat;
    private final DocumentRepository documents;
    private final JobRepository jobs;
    private final CvService cv;
    private final ObjectMapper mapper;

    public DocumentController(DocumentService service, DocumentChatService chat, DocumentRepository documents, JobRepository jobs, CvService cv,
                              ObjectMapper mapper) {
        this.service = service;
        this.chat = chat;
        this.documents = documents;
        this.jobs = jobs;
        this.cv = cv;
        this.mapper = mapper;
    }

    /** Starts generation. Poll {@code GET /api/runs/{runId}} for progress. */
    @PostMapping("/jobs/{id}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Long> generate(@PathVariable long id, @RequestBody(required = false) GenerateRequest body) {
        return Map.of("runId", service.start(id, body == null ? null : body.runId()));
    }

    @GetMapping("/jobs/{id}/documents")
    public JobDocuments forJob(@PathVariable long id) throws JsonProcessingException {
        var state = jobs.selectionState(id).orElseThrow(() -> new se.caibirch.jobpilot.common.ApiExceptions.NotFoundException("No job " + id));
        return new JobDocuments(documents.find(id, Kind.CV).map(this::view).orElse(null),
                documents.find(id, Kind.LETTER).map(this::view).orElse(null),
                state.selectionJson() == null ? null : mapper.readTree(state.selectionJson()), state.pinned(), state.excluded());
    }

    @PatchMapping("/jobs/{id}/selection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveSelection(@PathVariable long id, @RequestBody SelectionRequest r) {
        service.saveSelection(id, new LinkedHashSet<>(r.pinned() == null ? List.of() : r.pinned()),
                new LinkedHashSet<>(r.excluded() == null ? List.of() : r.excluded()));
    }

    @GetMapping("/documents")
    public List<DocumentRepository.Overview> overview() {
        return service.overview();
    }

    @GetMapping("/documents/{id}")
    public View get(@PathVariable long id) {
        return view(service.get(id));
    }

    @PutMapping("/documents/{id}")
    public View update(@PathVariable long id, @RequestBody JsonNode content) {
        return view(service.update(id, content));
    }

    /**
     * "Ask Claude": revises the document as the user asks, validates it and saves a new version. {@code content} is what the
     * user sees now (with unsaved edits); the stored content is used when it is absent.
     */
    @PostMapping("/documents/{id}/chat")
    public ChatResponse chat(@PathVariable long id, @RequestBody ChatRequest request) {
        DocumentChatService.Result r = chat.revise(id, request.message(), request.content(), request.history());
        return new ChatResponse(r.reply(), r.changed(), r.approvalRevoked(), view(r.document()));
    }

    @PostMapping("/documents/{id}/approve")
    public Report approve(@PathVariable long id) {
        return service.approve(id);
    }

    @GetMapping("/documents/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        DocumentRepository.Row row = service.get(id);
        String name = row.pdfPath() == null ? "document.pdf" : java.nio.file.Path.of(row.pdfPath()).getFileName().toString();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.pdf(id));
    }

    @ExceptionHandler(DocumentService.ApprovalRefused.class)
    ResponseEntity<Map<String, Object>> refused(DocumentService.ApprovalRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage(), "report", e.report()));
    }

    private View view(DocumentRepository.Row row) {
        try {
            JsonNode content = mapper.readTree(row.contentJson());
            Set<Long> cited = new LinkedHashSet<>();
            collect(content, cited);
            Map<Long, FactView> facts = new LinkedHashMap<>();
            for (Entry e : cv.entries()) {
                for (Fact f : e.facts()) {
                    if (cited.contains(f.id())) {
                        facts.put(f.id(), new FactView(f.id(), e.id(), e.titleEn() != null ? e.titleEn() : e.title(),
                                f.textEn(), f.textSv()));
                    }
                }
            }
            return new View(row.id(), row.jobId(), row.kind(), row.language(), row.version(), row.approved(), content,
                    row.reportJson() == null ? null : mapper.readTree(row.reportJson()), facts, row.updatedAt().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored document " + row.id(), e);
        }
    }

    private static void collect(JsonNode node, Set<Long> ids) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> {
                if ((f.getKey().equals("factIds") || f.getKey().equals("factIdsUsed")) && f.getValue().isArray()) {
                    f.getValue().forEach(n -> ids.add(n.asLong()));
                } else {
                    collect(f.getValue(), ids);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, ids));
        }
    }
}
