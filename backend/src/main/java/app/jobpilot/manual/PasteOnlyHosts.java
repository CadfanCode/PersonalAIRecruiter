package app.jobpilot.manual;

import java.net.URI;
import java.util.Locale;

import org.springframework.stereotype.Component;

import app.jobpilot.settings.JobPilotProperties;

/**
 * Sites that discourage bots (LinkedIn). JobPilot never fetches them and never opens them in its automated browser, because
 * that would be automated use of the user's real account on a site that forbids it. The user pastes the ad text instead and
 * applies there by hand.
 */
@Component
public class PasteOnlyHosts {

    private final JobPilotProperties props;

    public PasteOnlyHosts(JobPilotProperties props) {
        this.props = props;
    }

    /** True when the address is on one of the paste-only sites, or one of their subdomains. */
    public boolean matches(String url) {
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        for (String blocked : props.manual().pasteOnlyHosts()) {
            String b = blocked.toLowerCase(Locale.ROOT).trim();
            if (!b.isEmpty() && (host.equals(b) || host.endsWith("." + b))) {
                return true;
            }
        }
        return false;
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
