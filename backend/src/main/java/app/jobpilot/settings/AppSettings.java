package app.jobpilot.settings;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The editable settings, one text value per key (V9). Static configuration stays in application.yml. */
@Repository
public class AppSettings {

    private final JdbcClient jdbc;

    public AppSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public String get(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public Optional<Integer> getInt(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Integer.parseInt(v.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    public boolean getBoolean(String key, boolean fallback) {
        return get(key).map(v -> v.trim().equalsIgnoreCase("true")).orElse(fallback);
    }

    public void put(String key, String value) {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES (:k, :v) ON CONFLICT (key) DO UPDATE SET value = :v, updated_at = now()")
                .param("k", key).param("v", value).update();
    }

    public void put(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public void put(String key, int value) {
        put(key, Integer.toString(value));
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM app_setting WHERE key = :k").param("k", key).update();
    }
}
