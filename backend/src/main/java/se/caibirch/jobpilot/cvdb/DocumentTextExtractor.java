package se.caibirch.jobpilot.cvdb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Plain text out of PDF, DOCX and TXT/Markdown uploads. */
@Component
public class DocumentTextExtractor {

    private static final int MAX_DOCX_XML_BYTES = 20 * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    public String extract(String filename, byte[] bytes) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".pdf")) {
                return pdf(bytes);
            }
            if (name.endsWith(".docx")) {
                return docx(bytes);
            }
            if (name.endsWith(".txt") || name.endsWith(".md")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + filename + ": " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("Unsupported file type: " + filename + " (use PDF, DOCX, TXT or MD)");
    }

    private static String pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).strip();
        }
    }

    private static String docx(byte[] bytes) throws IOException {
        byte[] xml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if ("word/document.xml".equals(e.getName())) {
                    xml = readCapped(zip);
                    break;
                }
            }
        }
        if (xml == null) {
            throw new IOException("not a DOCX file (no word/document.xml)");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            StringBuilder out = new StringBuilder();
            NodeList paragraphs = doc.getElementsByTagNameNS(W, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                appendRuns(paragraphs.item(i), out);
                out.append('\n');
            }
            return out.toString().strip();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("invalid DOCX XML: " + e.getMessage(), e);
        }
    }

    private static void appendRuns(Node node, StringBuilder out) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && W.equals(el.getNamespaceURI())) {
                switch (el.getLocalName()) {
                    case "t" -> out.append(el.getTextContent());
                    case "tab" -> out.append('\t');
                    case "br" -> out.append('\n');
                    default -> appendRuns(el, out);
                }
            }
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_DOCX_XML_BYTES + 1);
        if (data.length > MAX_DOCX_XML_BYTES) {
            throw new IOException("document.xml is too large");
        }
        return data;
    }
}
