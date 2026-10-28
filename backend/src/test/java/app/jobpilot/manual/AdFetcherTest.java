package app.jobpilot.manual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import app.jobpilot.settings.JobPilotProperties;

/** The page reader's guards. WireMock plays the public site; no real address is ever fetched. */
class AdFetcherTest {

    static WireMockServer site = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        site.start();
    }

    @AfterAll
    static void stop() {
        site.stop();
    }

    static InetAddress ip(String address) {
        try {
            return InetAddress.getByName(address); // literal addresses only: no lookup happens
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static PasteOnlyHosts hosts(List<String> pasteOnly) {
        JobPilotProperties base = new JobPilotProperties(Path.of("x"), List.of(), new JobPilotProperties.Claude("claude", "", 1, 1, 1));
        return new PasteOnlyHosts(new JobPilotProperties(base.dataDir(), base.allowedOrigins(), base.claude(), base.search(), base.jobtech(),
                base.browser(), base.aktivitet(), new JobPilotProperties.Manual(pasteOnly, false, 0, 0)));
    }

    static AdFetcher fetcher(boolean allowPrivate, java.util.function.Function<String, InetAddress[]> resolver) {
        return new AdFetcher(new JobPilotProperties.Manual(List.of("linkedin.com"), allowPrivate, 100_000, 5),
                hosts(List.of("linkedin.com")), resolver);
    }

    @Test
    void onlyPublicAddressesAreRead() throws Exception {
        assertThat(AdFetcher.isPublic(ip("93.184.216.34"))).isTrue();
        assertThat(AdFetcher.isPublic(ip("2606:2800:220:1:248:1893:25c8:1946"))).isTrue();
        for (String blocked : List.of("127.0.0.1", "10.1.2.3", "192.168.0.10", "172.16.5.5", "169.254.169.254", "0.0.0.0",
                "100.64.0.1", "::1", "fe80::1", "fd00::1", "224.0.0.1")) {
            assertThat(AdFetcher.isPublic(ip(blocked))).as(blocked).isFalse();
        }
    }

    @Test
    void refusesOtherSchemesPrivateHostsAndPasteOnlySitesBeforeAnyRequest() throws Exception {
        AdFetcher f = fetcher(false, host -> new InetAddress[] {host.equals("internal.test") ? ip("10.0.0.5") : ip("93.184.216.34")});

        assertThatThrownBy(() -> f.fetch("file:///etc/passwd")).hasMessageContaining("http and https");
        assertThatThrownBy(() -> f.fetch("ftp://example.com/job")).hasMessageContaining("http and https");
        assertThatThrownBy(() -> f.fetch("http://internal.test/admin")).hasMessageContaining("private network");
        assertThatThrownBy(() -> f.fetch("https://www.linkedin.com/jobs/view/123")).hasMessageContaining("discourages automatic reading");
        assertThatThrownBy(() -> f.fetch("https://uk.linkedin.com/jobs/view/123")).hasMessageContaining("discourages automatic reading");
        assertThatThrownBy(() -> f.fetch("not a url")).isInstanceOf(AdFetcher.FetchException.class);
    }

    @Test
    void aPublicPageCannotRedirectTheReaderIntoTheLocalNetwork() throws Exception {
        site.stubFor(get(urlEqualTo("/bounce")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://internal.test/secret")));
        // "localhost" is made to look public so the first hop passes; the redirect target is private and must be refused
        AdFetcher f = fetcher(false, host -> new InetAddress[] {host.equals("internal.test") ? ip("10.0.0.5") : ip("93.184.216.34")});

        assertThatThrownBy(() -> f.fetch("http://localhost:" + site.port() + "/bounce")).hasMessageContaining("private network");
    }

    @Test
    void followsAPublicRedirectAndReturnsTheHtml() throws Exception {
        site.stubFor(get(urlEqualTo("/old")).willReturn(aResponse().withStatus(301).withHeader("Location", "/new")));
        site.stubFor(get(urlEqualTo("/new")).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody("<html><body><h1>Systemutvecklare</h1></body></html>")));
        AdFetcher f = fetcher(false, host -> new InetAddress[] {ip("93.184.216.34")});

        AdFetcher.Fetched fetched = f.fetch("http://localhost:" + site.port() + "/old");

        assertThat(fetched.url()).endsWith("/new");
        assertThat(fetched.html()).contains("Systemutvecklare");
    }

    @Test
    void explainsAnErrorStatusNonHtmlAndOversizedPages() throws Exception {
        site.stubFor(get(urlEqualTo("/blocked")).willReturn(aResponse().withStatus(403)));
        site.stubFor(get(urlEqualTo("/pdf")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF")));
        site.stubFor(get(urlEqualTo("/huge")).willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("x".repeat(200_000))));
        AdFetcher f = fetcher(false, host -> new InetAddress[] {ip("93.184.216.34")});

        assertThatThrownBy(() -> f.fetch("http://localhost:" + site.port() + "/blocked")).hasMessageContaining("answered 403");
        assertThatThrownBy(() -> f.fetch("http://localhost:" + site.port() + "/pdf")).hasMessageContaining("not a web page");
        assertThatThrownBy(() -> f.fetch("http://localhost:" + site.port() + "/huge")).hasMessageContaining("too large");
    }
}
