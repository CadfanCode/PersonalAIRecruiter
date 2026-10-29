package app.jobpilot.manual;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platsbanken ad pages are drawn by a script, so reading the page returns only the site's menu and cookie text. The ad itself
 * is read from the open JobTech API by the number in the address, the same source a search uses.
 */
final class PlatsbankenLinks {

    private static final Pattern AD = Pattern.compile(
            "(?i)^https?://(?:www\\.)?arbetsformedlingen\\.se/platsbanken/annonser/(\\d+)/?(?:[?#].*)?$");

    private PlatsbankenLinks() {
    }

    static Optional<String> adId(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher m = AD.matcher(url.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
