package se.caibirch.jobpilot.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.jobs.NewJob;

/** Uses a real JobTech response recorded on 26 Oct 2026. */
class JobTechMapperTest {

    ObjectMapper mapper = new ObjectMapper();

    JsonNode recorded() throws Exception {
        return mapper.readTree(Files.readString(Path.of("src/test/resources/jobtech/search-java-recorded.json")));
    }

    @Test
    void mapsTheFieldsListedInTheSpec() throws Exception {
        NewJob job = JobTechMapper.map(recorded().get("hits").get(0)).orElseThrow();

        assertThat(job.source()).isEqualTo("JOBTECH");
        assertThat(job.sourceId()).isEqualTo("31646689");
        assertThat(job.title()).isEqualTo("Senior Fullstackutvecklare Java");
        assertThat(job.employer()).isEqualTo("Quest Consulting Sverige AB");
        assertThat(job.clientEmployer()).as("same employer under another spelling").isNull();
        assertThat(job.municipality()).isEqualTo("Stockholm");
        assertThat(job.url()).isEqualTo("https://arbetsformedlingen.se/platsbanken/annonser/31646689");
        assertThat(job.applyUrl()).isEqualTo("https://pnty-apply.ponty-system.se/quest?id=8602&pnty_src=platsbanken");
        assertThat(job.employmentType()).isEqualTo("Heltid");
        assertThat(job.language()).isEqualTo("sv");
        assertThat(job.descriptionText()).startsWith("Vi söker två Seniora Fullstackutvecklare");
        assertThat(job.relevance()).isEqualTo(1.0);
        assertThat(job.dateUnverified()).isFalse();
        assertThat(job.rawJson()).contains("\"municipality_concept_id\"");
    }

    @Test
    void readsLocalSwedishTimesWithoutAZone() throws Exception {
        NewJob job = JobTechMapper.map(recorded().get("hits").get(0)).orElseThrow();

        // 2026-10-26T16:34:44 in Stockholm (CET, UTC+1 after the 25 Oct clock change)
        assertThat(job.publishedAt()).isEqualTo(Instant.parse("2026-10-26T15:34:44Z"));
        assertThat(job.deadline()).isEqualTo(Instant.parse("2026-11-23T22:59:59Z"));
    }

    @Test
    void skipsRemovedAdsAndAdsWithoutHeadline() throws Exception {
        var removed = (com.fasterxml.jackson.databind.node.ObjectNode) recorded().get("hits").get(0).deepCopy();
        removed.put("removed", true);
        var noHeadline = (com.fasterxml.jackson.databind.node.ObjectNode) recorded().get("hits").get(0).deepCopy();
        noHeadline.putNull("headline");

        assertThat(JobTechMapper.map(removed)).isEmpty();
        assertThat(JobTechMapper.map(noHeadline)).isEmpty();
    }

    @Test
    void clientEmployerIsNullWhenWorkplaceEqualsEmployer() throws Exception {
        var hit = (com.fasterxml.jackson.databind.node.ObjectNode) recorded().get("hits").get(1).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) hit.get("employer")).put("workplace", hit.get("employer").get("name").asText());

        assertThat(JobTechMapper.map(hit).orElseThrow().clientEmployer()).isNull();
    }

    @Test
    void theEndClientIsOnlyAWorkplaceThatNamesSomeoneElse() {
        assertThat(JobTechMapper.clientEmployer("SAAB Aktiebolag", "Saab", "Järfälla")).isNull();
        assertThat(JobTechMapper.clientEmployer("TIKK TAKK TIME SWEDEN AB", "Stockholm", "Stockholm")).isNull();
        assertThat(JobTechMapper.clientEmployer("Maxitech AB", "Maxitech ", "Stockholm")).isNull();
        assertThat(JobTechMapper.clientEmployer("Quest Consulting Sverige AB", "Quest Consulting AB", "Stockholm")).isNull();
        assertThat(JobTechMapper.clientEmployer("Comstream AB", "  ", "Stockholm")).isNull();
        assertThat(JobTechMapper.clientEmployer("Friday Väst AB", "Elektroniktest AB", "Järfälla")).isEqualTo("Elektroniktest AB");
        assertThat(JobTechMapper.clientEmployer("Comstream AB", " Taxi Stockholm ", "Stockholm")).isEqualTo("Taxi Stockholm");
        assertThat(JobTechMapper.clientEmployer(null, "X", null)).isNull();
    }

    @Test
    void remoteWorkplaceModelSetsTheHint() throws Exception {
        var hit = (com.fasterxml.jackson.databind.node.ObjectNode) recorded().get("hits").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) hit.get("workplace_model")).put("label", "Distans");

        assertThat(JobTechMapper.map(hit).orElseThrow().remoteHint()).isTrue();
    }
}
