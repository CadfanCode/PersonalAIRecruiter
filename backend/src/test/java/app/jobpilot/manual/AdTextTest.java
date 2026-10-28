package app.jobpilot.manual;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdTextTest {

    @Test
    void usesTheStructuredJobPostingFirstAndDropsMenusScriptsAndFooters() {
        String html = """
                <html><head><title>x</title>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"JobPosting","title":"Lärare i idrott och hälsa",
                 "hiringOrganization":{"@type":"Organization","name":"Nacka kommun"},
                 "jobLocation":{"@type":"Place","address":{"addressLocality":"Nacka","addressCountry":"SE"}},
                 "employmentType":["FULL_TIME"],"datePosted":"2026-10-20","validThrough":"2026-11-15",
                 "description":"<p>Du undervisar i <b>idrott</b>.</p><p>Behörighet krävs.</p>"}
                </script><script>var tracking = 1;</script></head>
                <body><nav>Meny Logga in</nav><main><h1>Lärare</h1><p>Välkommen till oss.</p></main><footer>Cookies</footer></body></html>""";

        String text = AdText.from(html);

        assertThat(text).startsWith("Title: Lärare i idrott och hälsa").contains("Employer: Nacka kommun", "Location: Nacka, SE",
                "Employment type: FULL_TIME", "Date posted: 2026-10-20", "Valid through (deadline): 2026-11-15",
                "Du undervisar i idrott.", "Behörighet krävs.", "Välkommen till oss.");
        assertThat(text).doesNotContain("Meny Logga in", "var tracking", "Cookies");
    }

    @Test
    void findsAJobPostingInsideAGraphOrAList() {
        String html = """
                <html><head><script type="application/ld+json">
                {"@graph":[{"@type":"WebSite","name":"Board"},{"@type":["JobPosting"],"title":"Backend Developer",
                 "hiringOrganization":{"name":"Acme"},"description":"Build APIs."}]}
                </script></head><body></body></html>""";

        assertThat(AdText.from(html)).contains("Title: Backend Developer", "Employer: Acme", "Build APIs.");
    }

    @Test
    void brokenStructuredDataIsIgnoredAndTheVisibleTextIsUsed() {
        String html = "<html><head><script type=\"application/ld+json\">{not json</script></head><body><p>Vi söker dig.</p></body></html>";

        assertThat(AdText.from(html)).isEqualTo("Vi söker dig.");
    }

    @Test
    void aShortPageIsNotAnAd() {
        assertThat(AdText.looksSubstantial("Please log in to see this job")).isFalse();
        assertThat(AdText.looksSubstantial("x".repeat(400))).isTrue();
        assertThat(AdText.looksSubstantial(null)).isFalse();
    }
}
