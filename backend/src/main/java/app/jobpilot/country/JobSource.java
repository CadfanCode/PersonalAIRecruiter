package app.jobpilot.country;

import java.util.List;
import java.util.function.BiConsumer;

import app.jobpilot.jobs.NewJob;
import app.jobpilot.search.SearchProfile;

/**
 * The job board(s) of one country. Adding a country means adding one bean that implements this interface and one entry in
 * {@link CountryRegistry#KNOWN}; the pipeline asks the registry for the source of the country the user chose.
 */
public interface JobSource {

    record Collected(List<NewJob> jobs, List<String> notes) {
    }

    /** ISO 3166-1 alpha-2, for example "SE". */
    String countryCode();

    /** What the user knows the board as, for example "Platsbanken". */
    String boardName();

    /** @param onQuery called with (queries done, queries total) for progress reporting */
    Collected collect(SearchProfile profile, BiConsumer<Integer, Integer> onQuery);
}
