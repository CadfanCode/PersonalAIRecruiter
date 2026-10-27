package se.caibirch.jobpilot.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.helper.W3CDom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.documents.DocumentModel.*;

/**
 * Thymeleaf HTML to PDF with openhtmltopdf (SPEC 8.5). Every value reaches the page through
 * {@code th:text}, so nothing Claude or the user wrote can inject markup. The HTML is parsed with jsoup and
 * handed to the renderer as a W3C DOM, which guarantees well-formed input.
 */
@Component
public class DocumentRenderer {

    public record Rendered(String html, byte[] pdf, int pages) {
    }

    private final TemplateEngine engine;
    private final String css;
    private final ObjectMapper mapper;

    public DocumentRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
        this.css = read("templates/document.css");
    }

    public Rendered renderCv(CvContent cv, Profile profile) {
        Context ctx = base(profile);
        Map<String, Object> view = mapper.convertValue(cv, new TypeReference<>() { });
        for (Object section : (List<?>) view.getOrDefault("sections", List.of())) {
            for (Object item : (List<?>) ((Map<?, ?>) section).get("items")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) item;
                m.put("rightSide", Stream.of(m.get("location"), m.get("dateLabel")).filter(x -> x != null && !x.toString().isBlank())
                        .map(Object::toString).collect(Collectors.joining(" | ")));
            }
        }
        ctx.setVariable("cv", view);
        boolean sv = "sv".equals(cv.language());
        ctx.setVariable("labels", Map.of("profile", sv ? "Profil" : "Profile", "languages", sv ? "Språk:" : "Languages:"));
        return render("cv", ctx);
    }

    public Rendered renderLetter(LetterContent letter, Profile profile) {
        Context ctx = base(profile);
        ctx.setVariable("letter", mapper.convertValue(letter, new TypeReference<Map<String, Object>>() { }));
        return render("letter", ctx);
    }

    private Context base(Profile p) {
        Context ctx = new Context();
        ctx.setVariable("name", p.fullName() == null ? "" : p.fullName().toUpperCase());
        ctx.setVariable("contact", contactLine(p));
        return ctx;
    }

    /** "Stockholm | 076-185 95 27 | caibirch@proton.me | www.caibirch.com | github.com/CadfanCode" */
    static String contactLine(Profile p) {
        return Stream.of(p.city(), p.phone(), p.email(), bare(p.website()), bare(p.github()), bare(p.linkedin()))
                .filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" | "));
    }

    private static String bare(String url) {
        return url == null ? null : url.replaceFirst("^https?://", "").replaceFirst("/$", "");
    }

    private Rendered render(String template, Context ctx) {
        String html = engine.process(template, ctx);
        Document parsed = Jsoup.parse(html);
        parsed.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        parsed.head().appendElement("style").appendChild(new DataNode(css));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            font(builder, "Inter-Regular.ttf", 400, FontStyle.NORMAL);
            font(builder, "Inter-SemiBold.ttf", 600, FontStyle.NORMAL);
            font(builder, "Inter-Bold.ttf", 700, FontStyle.NORMAL);
            font(builder, "Inter-Italic.ttf", 400, FontStyle.ITALIC);
            builder.withW3cDocument(new W3CDom().fromJsoup(parsed), "classpath:/templates/");
            builder.toStream(out);
            builder.run();
            byte[] pdf = out.toByteArray();
            return new Rendered(parsed.outerHtml(), pdf, pages(pdf));
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed", e);
        }
    }

    private static void font(PdfRendererBuilder builder, String file, int weight, FontStyle style) {
        builder.useFont(() -> {
            try {
                return new ClassPathResource("fonts/" + file).getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "Inter", weight, style, true);
    }

    static int pages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
