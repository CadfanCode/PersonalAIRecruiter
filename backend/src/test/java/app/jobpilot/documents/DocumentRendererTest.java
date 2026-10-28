package app.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import app.jobpilot.cvdb.CvModel.Language;
import app.jobpilot.cvdb.CvModel.Profile;
import app.jobpilot.documents.DocumentModel.*;

class DocumentRendererTest {

    DocumentRenderer renderer = new DocumentRenderer(new ObjectMapper());

    Profile profile = new Profile(1L, "Cai Birch", "Systemutvecklare", "caibirch@proton.me", "076-185 95 27", "+46 76 185 95 27",
            "Stockholm", "https://www.caibirch.com", "https://github.com/CadfanCode", null, null,
            List.of(new Language("English", "native")));

    static CvContent cv(String language, List<Section> sections) {
        return new CvContent(language, "Javautvecklare | Spring Boot, mikrotjänster och CI/CD",
                List.of("Systemutvecklare med inriktning mot backend i Java och Spring Boot, åäö ÅÄÖ.", "Söker en roll i ett erfaret Javateam."),
                sections, "Svenska (flytande) och engelska (modersmål)", "Övrig anställning 2019-2021, referenser lämnas på begäran");
    }

    static List<Section> sections() {
        return List.of(
                new Section("Tekniska kunskaper", "SKILLS", List.of(
                        new Item(0, "Java och backend", "Java 21, Kotlin, Spring Boot", null, "", List.of()))),
                new Section("Arbetslivserfarenhet", "EXPERIENCE", List.of(
                        new Item(1, "Systemutvecklare (praktik)", "Braive AB", "Stockholm", "2026", List.of(
                                new Bullet("Utvecklade och driftsatte mikrotjänster i Java och Kotlin.", List.of(10L)),
                                new Bullet("Hittade och åtgärdade race conditions i loggningsflödena.", List.of(11L)))))),
                new Section("Utbildning", "EDUCATION", List.of(
                        new Item(5, "Yrkeshögskoleexamen, mjukvaruutveckling med Java", "YH Akademin, Sundsvall", null, "2024-2026",
                                List.of(new Bullet("Tvåårig heltidsutbildning med fokus på backend i Java.", List.of(40L)))))));
    }

    static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    static Set<String> fontNames(byte[] pdf) throws IOException {
        Set<String> names = new TreeSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    names.add(font.getName().replaceFirst("^[A-Z]{6}\\+", ""));
                }
            }
        }
        return names;
    }

    @Test
    void rendersAnA4CvWithEmbeddedInterAndSwedishCharacters() throws IOException {
        var rendered = renderer.renderCv(cv("sv", sections()), profile);

        assertThat(new String(rendered.pdf(), 0, 5)).isEqualTo("%PDF-");
        assertThat(rendered.pages()).isEqualTo(1);
        String text = text(rendered.pdf());
        assertThat(text).contains("CAI BIRCH", "Javautvecklare | Spring Boot, mikrotjänster och CI/CD",
                "Stockholm | 076-185 95 27 | caibirch@proton.me | www.caibirch.com | github.com/CadfanCode",
                "åäö ÅÄÖ", "PROFIL", "TEKNISKA KUNSKAPER", "ARBETSLIVSERFARENHET", "UTBILDNING",
                "Java och backend: Java 21, Kotlin, Spring Boot", "Systemutvecklare (praktik) | Braive AB", "Stockholm | 2026",
                "Hittade och åtgärdade race conditions", "2024-2026", "Övrig anställning 2019-2021", "Språk: Svenska (flytande)");
        assertThat(fontNames(rendered.pdf())).allMatch(n -> n.startsWith("Inter"));
        assertThat(fontNames(rendered.pdf())).contains("Inter-Regular", "Inter-Bold", "Inter-SemiBold");
        try (PDDocument doc = Loader.loadPDF(rendered.pdf())) {
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(595f, org.assertj.core.data.Offset.offset(1f));
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isEqualTo(842f, org.assertj.core.data.Offset.offset(1f));
        }
    }

    @Test
    void theLanguagesLineIsTheLastSkillsBulletOrStandsAloneWithoutASkillsSection() throws IOException {
        String withSkills = text(renderer.renderCv(cv("sv", sections()), profile).pdf());
        assertThat(withSkills).containsOnlyOnce("Språk: Svenska (flytande)");
        assertThat(withSkills.indexOf("Språk:")).isLessThan(withSkills.indexOf("ARBETSLIVSERFARENHET"));

        String without = text(renderer.renderCv(cv("sv", sections().subList(1, 3)), profile).pdf());
        assertThat(without).containsOnlyOnce("Språk: Svenska (flytande)");
        assertThat(without.indexOf("Språk:")).isGreaterThan(without.indexOf("UTBILDNING"));
    }

    @Test
    void englishCvUsesEnglishLabels() throws IOException {
        var rendered = renderer.renderCv(cv("en", sections()), profile);

        assertThat(text(rendered.pdf())).contains("PROFILE", "Languages: Svenska");
    }

    @Test
    void markupInContentIsShownAsTextNeverInterpreted() throws IOException {
        var evil = List.of(new Section("Erfarenhet <b>x</b>", "EXPERIENCE", List.of(
                new Item(1, "Dev <script>alert(1)</script>", "A & B", "Stockholm", "2026", List.of(
                        new Bullet("<img src=x onerror=alert(1)> & </li></ul><h1>big</h1>", List.of(1L)))))));

        var rendered = renderer.renderCv(cv("sv", evil), profile);

        String text = text(rendered.pdf());
        assertThat(text).contains("<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "A & B", "</li></ul><h1>big</h1>");
        assertThat(rendered.html()).doesNotContain("<script>").doesNotContain("<img").contains("&lt;script&gt;");
    }

    @Test
    void aLongCvReportsMoreThanTwoPages() throws IOException {
        List<Bullet> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new Bullet("Punkt nummer " + i + " med en ganska lång text som fyller raden och tvingar fram en andra rad i dokumentet.", List.of(1L)));
        }
        var sections = List.of(new Section("Erfarenhet", "EXPERIENCE", List.of(new Item(1, "Titel", "Org", "Stockholm", "2026", many))));

        assertThat(renderer.renderCv(cv("sv", sections), profile).pages()).isGreaterThan(2);
    }

    @Test
    void rendersAOnePageLetter() throws IOException {
        var letter = new LetterContent("sv", "Ansökan: Junior Java-utvecklare", "Comstream AB, Stockholm", "Hej,",
                List.of("Jag är systemutvecklare med inriktning mot backend i Java och Spring Boot.", "Det tydligaste exemplet är Skepparklar."),
                "Vänliga hälsningar,", "Cai Birch", List.of(1L));

        var rendered = renderer.renderLetter(letter, profile);

        assertThat(rendered.pages()).isEqualTo(1);
        assertThat(text(rendered.pdf())).contains("CAI BIRCH", "Ansökan: Junior Java-utvecklare", "Comstream AB, Stockholm", "Hej,",
                "Det tydligaste exemplet är Skepparklar.", "Vänliga hälsningar,", "Cai Birch");
        assertThat(fontNames(rendered.pdf())).allMatch(n -> n.startsWith("Inter"));
    }

    @Test
    void contactLineSkipsMissingPartsAndUrlSchemes() {
        var p = new Profile(1L, "A", null, null, null, null, "Stockholm", "https://www.example.com/", null, null, null, List.of());

        assertThat(DocumentRenderer.contactLine(p)).isEqualTo("Stockholm | www.example.com");
    }

    @Test
    void writesPdfsForVisualInspectionWhenAsked() throws IOException {
        String dir = System.getProperty("jobpilot.visual.dir");
        if (dir == null) {
            return;
        }
        Files.write(Path.of(dir, "sample_cv.pdf"), renderer.renderCv(cv("sv", sections()), profile).pdf());
    }
}
