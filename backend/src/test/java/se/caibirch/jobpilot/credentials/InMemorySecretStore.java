package se.caibirch.jobpilot.credentials;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A stand-in for the OS credential store in tests; nothing ever touches the real keychain. */
public class InMemorySecretStore implements SecretStore {

    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public Map<String, String> contents() {
        return secrets;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String description() {
        return available ? "in-memory test store" : "none available";
    }

    @Override
    public void put(String account, String secret) {
        if (!available) {
            throw new SecretStoreException("No secure credential store is available on this computer, so nothing is saved.", null);
        }
        secrets.put(account, secret);
    }

    @Override
    public Optional<String> get(String account) {
        return Optional.ofNullable(secrets.get(account));
    }

    @Override
    public void delete(String account) {
        secrets.remove(account);
    }
}
