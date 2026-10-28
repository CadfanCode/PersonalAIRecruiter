package app.jobpilot.jobs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import app.jobpilot.jobs.JobModel.Row;

/** CSV and XLSX export of the results table. Ad text is third-party input, so cells are sanitised. */
public final class JobExport {

    static final List<String> HEADER = List.of("Rank", "Score", "Skills", "Experience", "Eligibility", "Title",
            "Employer", "End client", "Location", "Published", "Deadline", "Language", "Experience asked", "Fit",
            "Gaps", "Source", "Status", "Ad URL", "Apply URL");

    private JobExport() {
    }

    static List<List<Object>> table(List<Row> rows) {
        List<List<Object>> out = new ArrayList<>();
        out.add(new ArrayList<>(HEADER));
        for (Row r : rows) {
            var s = r.score();
            List<Object> cells = new ArrayList<>();
            cells.add(r.rank());
            cells.add(s == null ? null : s.total());
            cells.add(s == null ? null : s.skills());
            cells.add(s == null ? null : s.experience());
            cells.add(s == null ? null : s.eligibility());
            cells.add(r.title());
            cells.add(r.employer());
            cells.add(r.clientEmployer());
            cells.add(r.municipality());
            cells.add(r.publishedAt() == null ? null : r.publishedAt().toString().substring(0, 10));
            cells.add(r.deadline() == null ? null : r.deadline().toString().substring(0, 10));
            cells.add(r.language());
            cells.add(r.experienceRequiredText());
            cells.add(s == null ? null : s.fit());
            cells.add(s == null ? null : s.gaps());
            cells.add(r.source() + (r.dateUnverified() ? " (date unverified)" : ""));
            cells.add(r.status().name());
            cells.add(r.url());
            cells.add(r.applyUrl());
            out.add(cells);
        }
        return out;
    }

    // ---- CSV ----

    /** UTF-8 with a byte order mark so Excel shows åäö correctly. */
    public static byte[] csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        for (List<Object> line : table(rows)) {
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(csvCell(line.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!(value instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s; // stop spreadsheets from running a title or employer as a formula
        }
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ---- XLSX ----

    public static byte[] xlsx(List<Row> rows) {
        List<List<Object>> table = table(rows);
        StringBuilder sheet = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                <cols><col min="1" max="5" width="9" customWidth="1"/><col min="6" max="8" width="32" customWidth="1"/>\
                <col min="9" max="12" width="14" customWidth="1"/><col min="13" max="15" width="48" customWidth="1"/>\
                <col min="16" max="19" width="26" customWidth="1"/></cols>
                <sheetData>""");
        for (int r = 0; r < table.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            List<Object> line = table.get(r);
            for (int c = 0; c < line.size(); c++) {
                Object v = line.get(c);
                if (v == null) {
                    continue;
                }
                String ref = column(c) + (r + 1);
                String style = r == 0 ? " s=\"1\"" : "";
                if (v instanceof Number n) {
                    sheet.append("<c r=\"").append(ref).append('"').append(style).append("><v>").append(n).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style).append("><is><t xml:space=\"preserve\">")
                            .append(xml(v.toString())).append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="Jobs" sheetId="1" r:id="rId1"/></sheets></workbook>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
                    </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            sb.insert(0, (char) ('A' + i % 26));
        }
        return sb.toString();
    }

    /** Escapes XML and drops characters XML 1.0 forbids, which make Excel reject the whole file. */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            boolean valid = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!valid) {
                return;
            }
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
