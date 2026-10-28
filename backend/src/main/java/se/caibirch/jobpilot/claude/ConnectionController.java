package se.caibirch.jobpilot.claude;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import se.caibirch.jobpilot.credentials.SecretStore.SecretStoreException;

@RestController
@RequestMapping("/api/claude/connection")
public class ConnectionController {

    public record ProviderChoice(String id) {
    }

    public record ApiKeyInput(String key, String model) {
    }

    public record ModelInput(String model) {
    }

    public record TestRequest(String provider) {
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

    /** The key goes straight to the OS credential store and is never returned, logged or written to the database. */
    @PutMapping("/api-key")
    public ConnectionService.Status saveKey(@RequestBody ApiKeyInput input) {
        service.saveApiKey(input.key(), input.model());
        return service.status();
    }

    @DeleteMapping("/api-key")
    public ConnectionService.Status deleteKey() {
        service.deleteApiKey();
        return service.status();
    }

    @PutMapping("/api-model")
    public ConnectionService.Status model(@RequestBody ModelInput input) {
        service.apiModel(input.model());
        return service.status();
    }

    /** Always 200: {@code ok=false} carries what is wrong and how to fix it. */
    @PostMapping("/test")
    public ConnectionService.TestResult test(@RequestBody TestRequest request) {
        return service.test(request.provider());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecretStoreException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    java.util.Map<String, String> noSecureStore(SecretStoreException e) {
        return java.util.Map.of("error", e.getMessage());
    }
}
