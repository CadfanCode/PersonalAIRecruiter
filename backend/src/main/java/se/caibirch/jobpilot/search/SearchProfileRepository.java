package se.caibirch.jobpilot.search;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchProfileRepository {

    public record Stored(long id, String prompt, SearchProfile profile) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SearchProfileRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Profiles are never updated: scores are cached against the profile id. */
    public long insert(String prompt, SearchProfile profile) {
        try {
            return jdbc.sql("INSERT INTO search_profile (prompt, profile_json) VALUES (:p, :j::jsonb) RETURNING id")
                    .param("p", prompt).param("j", mapper.writeValueAsString(profile)).query(Long.class).single();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Long> latestId() {
        return jdbc.sql("SELECT id FROM search_profile ORDER BY id DESC LIMIT 1").query(Long.class).optional();
    }

    public Optional<Stored> find(long id) {
        return jdbc.sql("SELECT id, prompt, profile_json FROM search_profile WHERE id = :id").param("id", id)
                .query((rs, n) -> {
                    try {
                        return new Stored(rs.getLong("id"), rs.getString("prompt"),
                                mapper.readValue(rs.getString("profile_json"), SearchProfile.class));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Corrupt profile_json " + id, e);
                    }
                }).optional();
    }
}
