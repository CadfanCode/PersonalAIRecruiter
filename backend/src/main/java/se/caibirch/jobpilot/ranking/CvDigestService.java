package se.caibirch.jobpilot.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Fact;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.cvdb.CvModel.Skill;
import se.caibirch.jobpilot.cvdb.CvService;

/**
 * A compact JSON picture of the CV database for scoring prompts (SPEC 7.2), and a content hash used
 * as the "CV database version" in the score cache. Entry notes are owner to-dos and are left out.
 */
@Service
public class CvDigestService {

    public record Digest(String json, String version) {
    }

    private final CvService cv;
    private final ObjectMapper mapper;

    public CvDigestService(CvService cv, ObjectMapper mapper) {
        this.cv = cv;
        this.mapper = mapper;
    }

    public Digest build() {
        Profile p = cv.profile();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("headline", p.headline());
        candidate.put("city", p.city());
        candidate.put("languages", p.languages());
        candidate.put("workAuthorisation", p.workAuthorisation());

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Entry e : cv.entries()) {
            if (e.hidden()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("kind", e.kind());
            m.put("title", e.titleEn() != null ? e.titleEn() : e.title());
            m.put("organisation", e.organisation());
            m.put("from", e.startYm());
            m.put("to", e.ongoing() ? "present" : e.endYm());
            m.put("tags", e.tags());
            List<Map<String, Object>> facts = new ArrayList<>();
            for (Fact f : e.facts()) {
                facts.add(Map.of("id", f.id(), "text", f.textEn() != null ? f.textEn() : String.valueOf(f.textSv())));
            }
            m.put("facts", facts);
            entries.add(m);
        }

        Map<String, List<String>> skills = new TreeMap<>();
        for (Skill s : cv.skills()) {
            skills.computeIfAbsent(s.category() == null ? "other" : s.category(), k -> new ArrayList<>()).add(s.name());
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("candidate", candidate);
        digest.put("entries", entries);
        digest.put("skills", skills);
        digest.put("knownGaps", cv.gaps().stream().map(g -> g.text()).toList());
        try {
            String json = mapper.writeValueAsString(digest);
            return new Digest(json, sha256(json).substring(0, 16));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
