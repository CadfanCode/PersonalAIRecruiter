package se.caibirch.jobpilot.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.claude.JsonSchemas;

class SearchProfileSchemaTest {

    ObjectMapper mapper = new ObjectMapper();

    ObjectNode valid() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"roleSummary":"PE teacher","titles":["Idrottslärare"],"synonyms":[],"skillsWanted":["pedagogik"],
                 "seniority":"any","acceptExperienceUpToYears":5,
                 "locations":[{"name":"Nacka","municipality":"Nacka","radiusKm":0}],"remoteOk":false,
                 "languages":["sv"],"maxAgeDays":14,"employmentTypes":["heltid"],"exclusions":[],
                 "yrkesrollForAktivitetsrapport":"Idrottslärare","relevantTags":["teaching"],
                 "jobtechQueries":["idrottslärare"]}""");
    }

    @Test
    void aCompleteProfileIsValidAndBindsToTheRecord() throws Exception {
        assertThat(JsonSchemas.validate("searchProfile", valid())).isEmpty();

        SearchProfile p = mapper.treeToValue(valid(), SearchProfile.class);
        assertThat(p.locations().get(0).municipality()).isEqualTo("Nacka");
        assertThat(p.withMaxAgeDays(3).maxAgeDays()).isEqualTo(3);
        assertThat(p.maxAgeDays()).isEqualTo(14);
    }

    @Test
    void invalidEditsAreReported() throws Exception {
        ObjectNode badSeniority = valid().put("seniority", "wizard");
        ObjectNode noQueries = valid();
        noQueries.putArray("jobtechQueries");
        ObjectNode missing = valid();
        missing.remove("yrkesrollForAktivitetsrapport");
        ObjectNode extra = valid().put("surprise", 1);
        ObjectNode badAge = valid().put("maxAgeDays", 0);

        assertThat(JsonSchemas.validate("searchProfile", badSeniority)).isNotEmpty();
        assertThat(JsonSchemas.validate("searchProfile", noQueries)).isNotEmpty();
        assertThat(JsonSchemas.validate("searchProfile", missing)).isNotEmpty();
        assertThat(JsonSchemas.validate("searchProfile", extra)).isNotEmpty();
        assertThat(JsonSchemas.validate("searchProfile", badAge)).isNotEmpty();
    }

    @Test
    void scoreAndImportAndWebSchemasAreValidJsonSchemas() throws Exception {
        JsonNode emptyWeb = mapper.readTree("{\"jobs\":[]}");
        assertThat(JsonSchemas.validate("discoverJobsWeb", emptyWeb)).isEmpty();
        assertThat(JsonSchemas.validate("discoverJobsWeb", mapper.readTree("{}"))).isNotEmpty();
        assertThat(JsonSchemas.validate("scoreJob", mapper.readTree("{\"skills\":1}"))).isNotEmpty();
    }
}
