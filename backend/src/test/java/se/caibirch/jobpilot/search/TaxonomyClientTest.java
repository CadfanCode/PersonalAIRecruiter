package se.caibirch.jobpilot.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaxonomyClientTest {

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static final String MUNICIPALITIES = """
            [{"taxonomy/type":"municipality","taxonomy/id":"AvNB_uwa_6n6","taxonomy/preferred-label":"Stockholm"},
             {"taxonomy/type":"municipality","taxonomy/id":"zHxw_uJZ_NJ8","taxonomy/preferred-label":"Solna"},
             {"taxonomy/type":"municipality","taxonomy/id":"qm5H_jsD_fUF","taxonomy/preferred-label":"Järfälla"}]""";

    @BeforeAll
    static void start() {
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    @Test
    void resolvesNamesIgnoringCaseAndSkipsUnknownOnes() {
        wm.stubFor(get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(MUNICIPALITIES)));
        TaxonomyClient client = new TaxonomyClient(wm.baseUrl(), Duration.ofMillis(1));

        assertThat(client.municipalityIds(List.of("stockholm", "JÄRFÄLLA", "Atlantis", "Stockholm")))
                .containsExactly("AvNB_uwa_6n6", "qm5H_jsD_fUF");
    }

    @Test
    void loadsTheListOnlyOnce() {
        wm.stubFor(get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(MUNICIPALITIES)));
        TaxonomyClient client = new TaxonomyClient(wm.baseUrl(), Duration.ofMillis(1));

        client.municipalityIds(List.of("Solna"));
        client.municipalityIds(List.of("Stockholm"));

        wm.verify(1, getRequestedFor(urlPathEqualTo("/v1/taxonomy/main/concepts")));
    }

    @Test
    void emptyAnswerIsAnError() {
        wm.stubFor(get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("[]")));

        assertThatThrownBy(() -> new TaxonomyClient(wm.baseUrl(), Duration.ofMillis(1)).municipalityIds(List.of("Solna")))
                .hasMessageContaining("no municipalities");
    }
}
