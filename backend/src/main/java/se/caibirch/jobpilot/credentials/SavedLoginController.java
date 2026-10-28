package se.caibirch.jobpilot.credentials;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import se.caibirch.jobpilot.credentials.SavedLoginService.View;

/** Settings, Saved logins. The password goes in and never comes back out: only host and username are ever returned. */
@RestController
@RequestMapping("/api/logins")
public class SavedLoginController {

    public record LoginInput(String host, String username, String password) {
        @Override
        public String toString() {
            return "LoginInput[host=" + host + ", username=" + username + ", password=***]";
        }
    }

    private final SavedLoginService service;

    public SavedLoginController(SavedLoginService service) {
        this.service = service;
    }

    @GetMapping
    public List<View> list() {
        return service.list();
    }

    @PutMapping
    public View save(@RequestBody LoginInput in) {
        return service.save(in.host(), in.username(), in.password());
    }

    @DeleteMapping("/{host}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String host) {
        service.delete(host);
    }

    @ExceptionHandler(SecretStore.SecretStoreException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> noSecureStore(SecretStore.SecretStoreException e) {
        return Map.of("error", e.getMessage());
    }
}
