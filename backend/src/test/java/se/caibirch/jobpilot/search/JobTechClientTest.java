package se.caibirch.jobpilot.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobTechClientTest {

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

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

    JobTechClient client(String apiKey) {
        return new JobTechClient(wm.baseUrl(), apiKey, 100, Duration.ofMillis(1));
    }

    static String page(int from, int count, int total) {
        String hits = IntStream.range(from, from + count).mapToObj(i -> "{\"id\":\"" + i + "\",\"headline\":\"Job " + i + "\"}")
                .collect(Collectors.joining(","));
        return "{\"total\":{\"value\":" + total + "},\"hits\":[" + hits + "]}";
    }

    @Test
    void sendsTheDocumentedParameters() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(page(0, 2, 2))));

        List<JsonNode> hits = client("").search("java utvecklare", 7, List.of("AvNB_uwa_6n6", "zHxw_uJZ_NJ8"), false, 50);

        assertThat(hits).hasSize(2);
        wm.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withQueryParam("q", equalTo("java utvecklare"))
                .withQueryParam("published-after", equalTo("10080"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("offset", equalTo("0"))
                .withQueryParam("municipality", equalTo("AvNB_uwa_6n6"))
                .withQueryParam("municipality", equalTo("zHxw_uJZ_NJ8"))
                .withoutHeader("api-key"));
    }

    @Test
    void encodesSpecialCharactersInTheQuery() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(page(0, 1, 1))));

        client("").search("c++ & .net", 3, List.of(), false, 10);

        wm.verify(getRequestedFor(urlPathEqualTo("/search")).withQueryParam("q", equalTo("c++ & .net")));
    }

    @Test
    void pagesUntilTheTotalIsReached() {
        wm.stubFor(get(urlPathEqualTo("/search")).withQueryParam("offset", equalTo("0")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(page(0, 100, 130))));
        wm.stubFor(get(urlPathEqualTo("/search")).withQueryParam("offset", equalTo("100")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(page(100, 30, 130))));

        assertThat(client("").search("java", 7, List.of(), false, 300)).hasSize(130);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void stopsAtMaxHits() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(page(0, 100, 5000))));

        assertThat(client("").search("java", 7, List.of(), false, 100)).hasSize(100);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void remotePassAddsTheRemoteParameter() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(page(0, 1, 1))));

        client("").search("java", 7, List.of(), true, 10);

        wm.verify(getRequestedFor(urlPathEqualTo("/search")).withQueryParam("remote", equalTo("true")));
    }

    @Test
    void sendsTheOptionalApiKeyOnlyWhenConfigured() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(page(0, 1, 1))));

        client("secret").search("java", 7, List.of(), false, 10);

        wm.verify(getRequestedFor(urlPathEqualTo("/search")).withHeader("api-key", equalTo("secret")));
    }

    @Test
    void retriesOn429AndServerErrorsThenSucceeds() {
        wm.stubFor(get(urlPathEqualTo("/search")).inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429)).willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo("/search")).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("third"));
        wm.stubFor(get(urlPathEqualTo("/search")).inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(page(0, 1, 1))));

        assertThat(client("").search("java", 7, List.of(), false, 10)).hasSize(1);
        wm.verify(3, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void givesUpAfterFiveAttempts() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client("").search("java", 7, List.of(), false, 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed after 5 attempts");
        wm.verify(5, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void doesNotRetryClientErrors() {
        wm.stubFor(get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client("").search("java", 7, List.of(), false, 10))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void rateLimiterSpacesCalls() {
        RateLimiter limiter = new RateLimiter(20); // 50 ms apart
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(190);
    }
}
