package se.caibirch.jobpilot.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import se.caibirch.jobpilot.country.JobSource;
import se.caibirch.jobpilot.jobs.NewJob;

/** Runs the profile's JobTech queries and returns the distinct ads, best relevance kept per ad. */
@Component
public class CandidateCollector implements JobSource {

    static final int HITS_PER_QUERY = 300;
    static final int REMOTE_HITS_PER_QUERY = 100;

    private final JobTechClient jobTech;
    private final TaxonomyClient taxonomy;

    public CandidateCollector(JobTechClient jobTech, TaxonomyClient taxonomy) {
        this.jobTech = jobTech;
        this.taxonomy = taxonomy;
    }

    @Override
    public String countryCode() {
        return "SE";
    }

    @Override
    public String boardName() {
        return "Platsbanken";
    }

    /** @param onQuery called with (queries done, queries total) for progress reporting */
    @Override
    public Collected collect(SearchProfile profile, BiConsumer<Integer, Integer> onQuery) {
        List<String> notes = new ArrayList<>();
        List<String> names = profile.locations().stream().map(SearchProfile.Location::municipality).distinct().toList();
        List<String> ids = List.of();
        if (!names.isEmpty()) {
            try {
                ids = taxonomy.municipalityIds(names);
            } catch (RuntimeException e) {
                notes.add("Could not look up municipalities (" + e.getMessage() + "); searching nationwide");
            }
            if (ids.size() < names.size() && !ids.isEmpty()) {
                notes.add("Some municipalities were not found in the taxonomy and are ignored in the search");
            }
            if (ids.isEmpty()) {
                notes.add("No municipality resolved; searching nationwide and filtering by location afterwards");
            }
        }

        Map<String, NewJob> bySourceId = new LinkedHashMap<>();
        int failures = 0;
        List<String> queries = profile.jobtechQueries();
        for (int i = 0; i < queries.size(); i++) {
            String q = queries.get(i);
            try {
                add(bySourceId, jobTech.search(q, profile.maxAgeDays(), ids, false, HITS_PER_QUERY), false);
                if (profile.remoteOk()) {
                    add(bySourceId, jobTech.search(q, profile.maxAgeDays(), List.of(), true, REMOTE_HITS_PER_QUERY), true);
                }
            } catch (RuntimeException e) {
                failures++;
                notes.add("Query '" + q + "' failed: " + e.getMessage());
            }
            onQuery.accept(i + 1, queries.size());
        }
        if (failures == queries.size()) {
            throw new IllegalStateException("Every JobTech query failed: " + String.join("; ", notes));
        }
        return new Collected(new ArrayList<>(bySourceId.values()), notes);
    }

    private static void add(Map<String, NewJob> into, List<JsonNode> hits, boolean remotePass) {
        for (JsonNode hit : hits) {
            JobTechMapper.map(hit).ifPresent(job -> {
                NewJob candidate = remotePass ? withRemoteHint(job) : job;
                into.merge(job.sourceId(), candidate, (a, b) -> a.relevance() >= b.relevance() ? a : b);
            });
        }
    }

    private static NewJob withRemoteHint(NewJob j) {
        return new NewJob(j.source(), j.sourceId(), j.url(), j.applyUrl(), j.title(), j.employer(), j.clientEmployer(),
                j.location(), j.municipality(), j.publishedAt(), j.deadline(), j.language(), j.employmentType(),
                j.descriptionText(), j.rawJson(), j.dateUnverified(), j.evidenceUrl(), j.relevance(), true);
    }
}
