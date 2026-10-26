package se.caibirch.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobModel.Score;
import se.caibirch.jobpilot.jobs.JobModel.Status;

class JobExportTest {

    static Row row(String title, String employer, String fit) {
        Score score = new Score(83, 40, 25, 18, List.of(), fit, "No Angular", "m", Instant.EPOCH);
        return new Row(1, 1, "JOBTECH", "1", "https://platsbanken/1", null, title, employer, "Taxi Stockholm", "Solna",
                "Solna", Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-11-20T10:00:00Z"), "sv", null,
                false, "2 års erfarenhet", Status.NEW, null, false, null, score);
    }

    @Test
    void csvQuotesCommasQuotesAndNewlines() {
        String csv = new String(JobExport.csv(List.of(row("Java, junior", "Acme \"AB\"", "line1\nline2"))), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿Rank,Score,Tech");
        assertThat(csv).contains("\"Java, junior\"").contains("\"Acme \"\"AB\"\"\"").contains("\"line1\nline2\"");
        assertThat(csv).contains("1,83,40,25,18,");
    }

    @Test
    void csvNeutralisesFormulaInjectionInThirdPartyText() {
        String csv = new String(JobExport.csv(List.of(row("=HYPERLINK(\"http://evil\")", "+1 AB", "@SUM(A1)"))), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=HYPERLINK(").contains("'+1 AB").contains("'@SUM(A1)");
    }

    @Test
    void csvDoesNotMangleNegativeNumbers() {
        assertThat(JobExport.csvCell(-3)).isEqualTo("-3");
        assertThat(JobExport.csvCell("-3 days")).isEqualTo("'-3 days");
    }

    @Test
    void xlsxIsAWellFormedPackageWithTheRowsAndEscapedText() throws Exception {
        byte[] xlsx = JobExport.xlsx(List.of(row("R&D <Java> dev\u0001\u000B", "Åkerbär AB", "ok"), row("Second", "B", "fine")));

        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                parts.put(e.getName(), zip.readAllBytes());
            }
        }
        assertThat(parts.keySet()).containsExactlyInAnyOrder("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
        for (byte[] xml : parts.values()) {
            parse(xml); // every part must be well-formed XML, or Excel refuses the file
        }
        Document sheet = parse(parts.get("xl/worksheets/sheet1.xml"));
        assertThat(sheet.getElementsByTagName("row").getLength()).isEqualTo(3);
        NodeList texts = sheet.getElementsByTagName("t");
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < texts.getLength(); i++) {
            all.add(texts.item(i).getTextContent());
        }
        assertThat(all).contains("R&D <Java> dev", "Åkerbär AB", "Title", "Second");
    }

    @Test
    void columnLetters() {
        assertThat(JobExport.column(0)).isEqualTo("A");
        assertThat(JobExport.column(18)).isEqualTo("S");
        assertThat(JobExport.column(26)).isEqualTo("AA");
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    @SuppressWarnings("unused")
    private static void unused() throws IOException {
    }
}
