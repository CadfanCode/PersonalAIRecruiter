package se.caibirch.jobpilot.manual;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Reads one public ad page the user pasted the address of. It is a single request on the user's behalf, not a crawler, and it
 * is careful: only http and https, only public addresses (re-checked at every redirect, so a public page cannot bounce it to the
 * local network), a size and time limit, and no cookies or login. Sites on the paste-only list are never fetched.
 */
@Component
public class AdFetcher {

    /** The page could not be read automatically; the message says why and the user can paste the text instead. */
    public static class FetchException extends RuntimeException {
        public FetchException(String message) {
            super(message);
        }
    }

    public record Fetched(String url, String html) {
    }

    private static final int MAX_REDIRECTS = 5;

    private final JobPilotProperties.Manual config;
    private final PasteOnlyHosts pasteOnly;
    private final Function<String, InetAddress[]> resolver;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public AdFetcher(JobPilotProperties props, PasteOnlyHosts pasteOnly) {
        this(props.manual(), pasteOnly, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new FetchException("The address " + host + " does not exist");
            }
        });
    }

    AdFetcher(JobPilotProperties.Manual config, PasteOnlyHosts pasteOnly, Function<String, InetAddress[]> resolver) {
        this.config = config;
        this.pasteOnly = pasteOnly;
        this.resolver = resolver;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(config.fetchTimeoutSeconds())).build();
    }

    public Fetched fetch(String url) {
        URI uri = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check(uri);
            HttpResponse<InputStream> response;
            try {
                response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(config.fetchTimeoutSeconds()))
                        .header("User-Agent", "JobPilot/1.0 (personal job search tool; one page read on the user's request)")
                        .header("Accept", "text/html,application/xhtml+xml").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new FetchException("Could not reach the page: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while reading the page");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElseThrow(() -> new FetchException("The page redirected nowhere"));
                uri = parse(uri.resolve(location).toString());
                continue;
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new FetchException("The site answered " + status + " (many sites block automatic reading)");
            }
            String type = response.headers().firstValue("Content-Type").orElse("text/html").toLowerCase(Locale.ROOT);
            if (!(type.contains("html") || type.contains("xml"))) {
                closeQuietly(response.body());
                throw new FetchException("The address is not a web page (" + type + ")");
            }
            return new Fetched(uri.toString(), read(response.body(), type));
        }
        throw new FetchException("The page redirected too many times");
    }

    /** Throws unless the address may be fetched. Called for the pasted address and for every redirect target. */
    void check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new FetchException("Only http and https addresses can be read");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchException("The address has no host name");
        }
        if (pasteOnly.matches(uri.toString())) {
            throw new FetchException(host + " discourages automatic reading, so JobPilot does not fetch it");
        }
        if (config.allowPrivateHosts()) {
            return;
        }
        for (InetAddress address : resolver.apply(host)) {
            if (!isPublic(address)) {
                throw new FetchException("That address points inside a private network and is not read");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first != 0 && !(first == 100 && second >= 64 && second <= 127); // 0.0.0.0/8 and carrier-grade NAT
        }
        return (b[0] & 0xfe) != 0xfc; // IPv6 unique local fc00::/7
    }

    private static URI parse(String url) {
        try {
            return URI.create(url.strip());
        } catch (RuntimeException e) {
            throw new FetchException("That is not a valid address");
        }
    }

    private String read(InputStream in, String type) {
        try (in) {
            byte[] bytes = in.readNBytes(config.maxPageBytes() + 1);
            if (bytes.length > config.maxPageBytes()) {
                throw new FetchException("The page is too large to read");
            }
            Charset charset = StandardCharsets.UTF_8;
            int i = type.indexOf("charset=");
            if (i >= 0) {
                try {
                    charset = Charset.forName(type.substring(i + 8).replaceAll("[\";\\s].*", ""));
                } catch (RuntimeException e) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            return new String(bytes, charset);
        } catch (IOException e) {
            throw new FetchException("The page could not be read: " + e.getMessage());
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
