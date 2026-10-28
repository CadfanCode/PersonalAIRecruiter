package app.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    DocumentTextExtractor extractor = new DocumentTextExtractor();

    static byte[] pdf(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16);
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] docx(String documentXml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void readsPdfText() throws IOException {
        String text = extractor.extract("cv.pdf", pdf("Software Developer, Braive AB", "Utvecklade mikrotjänster"));

        assertThat(text).contains("Software Developer, Braive AB").contains("Utvecklade mikrotjänster");
    }

    @Test
    void readsDocxParagraphsRunsAndTabs() throws IOException {
        String xml = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:r><w:t>Braive</w:t></w:r><w:r><w:t xml:space="preserve"> AB</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>2026</w:t></w:r></w:p>
                <w:p><w:r><w:t>Built microservices</w:t></w:r></w:p></w:body></w:document>""";

        assertThat(extractor.extract("Letter.DOCX", docx(xml))).isEqualTo("Braive AB\t2026\nBuilt microservices");
    }

    @Test
    void rejectsDocxWithDoctype() throws IOException {
        String xml = "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>";

        assertThatThrownBy(() -> extractor.extract("evil.docx", docx(xml))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTextAndMarkdownAsUtf8() {
        assertThat(extractor.extract("notes.txt", "Jag är ingenjör".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("Jag är ingenjör");
        assertThat(extractor.extract("notes.md", "# Hej".getBytes(StandardCharsets.UTF_8))).isEqualTo("# Hej");
    }

    @Test
    void rejectsUnknownTypesAndBrokenFiles() {
        assertThatThrownBy(() -> extractor.extract("photo.png", new byte[] {1})).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> extractor.extract("broken.pdf", new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("broken.pdf");
        assertThatThrownBy(() -> extractor.extract("empty.docx", new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
