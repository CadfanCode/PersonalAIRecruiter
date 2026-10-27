# 0004 How documents are generated and checked

Date: 2026-10-27. Status: accepted.

- **PDF stack.** `io.github.openhtmltopdf:openhtmltopdf-pdfbox` (the PDFBox 3 line) with Thymeleaf
  and jsoup. The original `com.openhtmltopdf` artifacts are on PDFBox 2 and clash with the PDFBox 3
  already used for CV import. Exactly one PDFBox version resolves (3.0.8).
- **Fonts.** Static Inter Regular, SemiBold, Bold and Italic from the rsms/inter 4.0 release (OFL
  licence bundled). Variable fonts do not give openhtmltopdf separate weights.
- **Templates take maps, not records.** The standalone Thymeleaf engine uses OGNL, which cannot read
  Java records; content is converted to maps first. Every value is rendered with `th:text`.
- **Java owns facts, Claude owns prose.** Titles, employers, places and dates come from the CV
  database after generation, so the validators do not need to check them.
- **FAIL versus WARN.** FAIL is what SPEC 8.3 lists plus language mismatch and foreign employers.
  Fuzzy checks (numbers, skills) only warn, because they fire on documents a human approved.
- **One rewrite.** Any finding triggers one `rewriteForViolations`; Java-owned fields are restored
  afterwards; what remains is shown for manual editing and blocks approval only if it is a FAIL.
- **Own selection rules in Java.** Pins, exclusions, hidden entries and education are enforced after
  `selectFacts`, so a wrong answer cannot leak an excluded or invented entry.
