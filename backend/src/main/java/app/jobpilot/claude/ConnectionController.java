package app.jobpilot.claude;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claude/connection")
public class ConnectionController {

    public record ProviderChoice(String id) {
    }

    public record ModelInput(String model) {
    }

    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public ConnectionService.Status status() {
        return service.status();
    }

    @PutMapping("/provider")
    public ConnectionService.Status select(@RequestBody ProviderChoice choice) {
        service.select(choice.id());
        return service.status();
    }

    /** Claude Code model alias (sonnet, opus, fable) or blank for the CLI's own default. */
    @PutMapping("/cli-model")
    public ConnectionService.Status cliModel(@RequestBody ModelInput input) {
        service.cliModel(input.model());
        return service.status();
    }

    /** Always 200: {@code ok=false} carries what is wrong and how to fix it. */
    @PostMapping("/test")
    public ConnectionService.TestResult test() {
        return service.test();
    }
}
