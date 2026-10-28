package se.caibirch.jobpilot.claude;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** The {@link ClaudeRunner} the rest of the app uses: every call goes to the provider the user chose. */
@Component
public class ProviderRouter implements ClaudeRunner {

    private final List<AiProvider> providers;
    private final ProviderSettings settings;

    public ProviderRouter(List<AiProvider> providers, ProviderSettings settings) {
        this.providers = providers;
        this.settings = settings;
    }

    public AiProvider selected() {
        return provider(settings.selectedId());
    }

    public AiProvider provider(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No AI provider " + id));
    }

    public List<AiProvider> all() {
        return providers;
    }

    @Override
    public <T> T run(ClaudeTask<T> task) {
        return selected().run(task);
    }

    @Override
    public Optional<UsageLimits> limits() {
        return selected().limits();
    }
}
