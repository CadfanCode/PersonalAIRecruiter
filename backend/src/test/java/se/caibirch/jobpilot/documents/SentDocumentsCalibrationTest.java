package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.cvdb.DocumentTextExtractor;
import se.caibirch.jobpilot.documents.DocumentModel.Severity;
import se.caibirch.jobpilot.documents.DocumentModel.Violation;

/**
 * The ten PDFs that were actually sent on 26 Oct 2026 were approved by a human, so the style
 * validator must find no FAIL in them. If it does, the rule is too strict or the PDFs have a
 * real problem; either way the failure message shows which text matched.
 */
class SentDocumentsCalibrationTest {

    static final List<String> BANNED = List.of("passionate about", "I am excited to", "thrilled", "dynamic", "synergy",
            "leverage", "spearheaded", "proven track record", "results-driven", "fast-paced environment",
            "not only ... but also", "in today's", "delve", "tapestry", "I believe I would be a great fit", "brinner för",
            "passionerad", "drivs av", "inte bara ... utan också", "i dagens", "dynamisk", "resultatinriktad",
            "jag är övertygad om att");

    Path examples() {
        for (String p : List.of("../seed/examples", "seed/examples")) {
            if (Files.isDirectory(Path.of(p))) {
                return Path.of(p);
            }
        }
        return null;
    }

    @Test
    void sentDocumentsHaveNoStyleFailures() throws IOException {
        Path dir = examples();
        assumeTrue(dir != null, "seed/examples is not available");
        DocumentTextExtractor extractor = new DocumentTextExtractor();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path pdf : files.filter(f -> f.toString().endsWith(".pdf")).sorted().toList()) {
                String text = extractor.extract(pdf.getFileName().toString(), Files.readAllBytes(pdf));
                boolean letter = pdf.getFileName().toString().startsWith("brev");
                Map<String, String> strings = new LinkedHashMap<>();
                String[] paragraphs = text.split("\\n\\s*\\n");
                for (int i = 0; i < paragraphs.length; i++) {
                    strings.put("paragraphs[" + i + "]", paragraphs[i].replaceAll("\\s*\\n\\s*", " "));
                }
                // The letters open with "Ansökan: ..." and an employer line before "Hej,"; the greeting is not a paragraph.
                var violations = new DocumentStyleValidator(false, BANNED, Set.of()).validate(strings, false);
                for (Violation v : violations) {
                    String line = pdf.getFileName() + " " + v.where() + " [" + v.code() + "] " + v.text();
                    (v.severity() == Severity.FAIL ? problems : warnings).add(line);
                }
                if (letter) {
                    String firstSentence = text.substring(text.indexOf("\n", text.indexOf("Hej")) + 1).strip();
                    assertThat(firstSentence).as(pdf + " must not open with a name introduction")
                            .doesNotStartWith("Jag heter").doesNotStartWith("My name is");
                }
                checked++;
            }
        }
        System.out.println("Calibration warnings on sent documents: " + warnings);
        assertThat(checked).isEqualTo(10);
        assertThat(problems).as("FAIL findings in documents that were sent").isEmpty();
    }
}
