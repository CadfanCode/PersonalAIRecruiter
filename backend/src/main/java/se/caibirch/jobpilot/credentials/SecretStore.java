package se.caibirch.jobpilot.credentials;

import java.util.Optional;

/**
 * The only place a secret (the Anthropic API key, a saved portal password) is ever kept: the operating system's credential
 * store. Never the database, a file or a log (CLAUDE.md rules 2 and 6).
 */
public interface SecretStore {

    /** False when this machine has no usable credential store (for example Linux without a Secret Service running). */
    boolean available();

    /** A human description for the UI, for example "macOS Keychain". */
    String description();

    /** @throws SecretStoreException when the store is unavailable or refuses */
    void put(String account, String secret);

    Optional<String> get(String account);

    void delete(String account);

    class SecretStoreException extends RuntimeException {
        public SecretStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
