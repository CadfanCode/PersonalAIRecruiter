package app.jobpilot.credentials;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import app.jobpilot.common.ApiExceptions.NotFoundException;

/**
 * Logins the user chose to save for employer portals (ADR 0016). The index (host and username) is in the database; the password
 * is only in the OS credential store, under an account named after the host. One login per host.
 */
@Service
public class SavedLoginService {

    public record View(String host, String username, Instant createdAt, Instant lastUsedAt) {
    }

    /** A password in memory for the moment it is typed. toString never shows it. */
    public record Credentials(String host, String username, String password) {
        @Override
        public String toString() {
            return "Credentials[host=" + host + ", username=" + username + ", password=***]";
        }
    }

    private static final Pattern HOST = Pattern.compile("^([a-z0-9]([a-z0-9-]*[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    private final JdbcClient jdbc;
    private final SecretStore secrets;

    public SavedLoginService(JdbcClient jdbc, SecretStore secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    /** "https://Jobs.Example.com:8443/login?x=1" and "jobs.example.com" both become "jobs.example.com". */
    public static String normaliseHost(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("A web address is required");
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        String host = text;
        if (text.contains("/") || text.contains(":")) {
            try {
                host = URI.create(text.contains("://") ? text : "https://" + text).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
        }
        if (host == null || host.length() > 253 || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("That is not a valid web address: use the address of the portal's login page, for example jobs.example.com");
        }
        return host;
    }

    static String account(String host) {
        return "portal:" + host;
    }

    public List<View> list() {
        return jdbc.sql("SELECT host, username, created_at, last_used_at FROM saved_login ORDER BY host")
                .query((rs, n) -> new View(rs.getString("host"), rs.getString("username"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant())).list();
    }

    /** Saves (or replaces) the login for one host. The password is stored first: if the credential store refuses, nothing is indexed. */
    public View save(String hostInput, String username, String password) {
        String host = normaliseHost(hostInput);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A username or email is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("A password is required");
        }
        secrets.put(account(host), password);
        jdbc.sql("""
                INSERT INTO saved_login (host, username) VALUES (:h, :u)
                ON CONFLICT (host) DO UPDATE SET username = :u, created_at = now(), last_used_at = NULL
                """).param("h", host).param("u", username.trim()).update();
        return list().stream().filter(v -> v.host().equals(host)).findFirst().orElseThrow();
    }

    public void delete(String hostInput) {
        String host = normaliseHost(hostInput);
        if (jdbc.sql("DELETE FROM saved_login WHERE host = :h").param("h", host).update() == 0) {
            throw new NotFoundException("No saved login for " + host);
        }
        secrets.delete(account(host));
    }

    /** The saved login for exactly this host (no parent domains, no "www" guessing), if there is one and it can be read. */
    public Optional<Credentials> credentialsFor(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase(Locale.ROOT);
        Optional<String> username = jdbc.sql("SELECT username FROM saved_login WHERE host = :h").param("h", h).query(String.class).optional();
        if (username.isEmpty() || !secrets.available()) {
            return Optional.empty();
        }
        return secrets.get(account(h)).map(p -> new Credentials(h, username.get(), p));
    }

    public boolean has(String host) {
        return host != null && jdbc.sql("SELECT count(*) FROM saved_login WHERE host = :h").param("h", host.toLowerCase(Locale.ROOT))
                .query(Long.class).single() > 0;
    }

    public void markUsed(String host) {
        jdbc.sql("UPDATE saved_login SET last_used_at = :now WHERE host = :h").param("now", Timestamp.from(Instant.now()))
                .param("h", host.toLowerCase(Locale.ROOT)).update();
    }
}
