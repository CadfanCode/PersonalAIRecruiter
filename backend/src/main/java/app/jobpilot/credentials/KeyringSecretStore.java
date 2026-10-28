package app.jobpilot.credentials;

import java.util.Optional;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Keychain, Credential Manager or Secret Service through java-keyring; every entry is filed under the service "JobPilot". */
@Component
public class KeyringSecretStore implements SecretStore {

    static final String SERVICE = "JobPilot";
    private static final Logger log = LoggerFactory.getLogger(KeyringSecretStore.class);

    private volatile Keyring keyring;
    private volatile boolean tried;

    private Keyring keyring() {
        if (!tried) {
            synchronized (this) {
                if (!tried) {
                    try {
                        keyring = Keyring.create();
                    } catch (BackendNotSupportedException | RuntimeException | LinkageError e) {
                        log.warn("No OS credential store available", e);
                    }
                    tried = true;
                }
            }
        }
        return keyring;
    }

    @Override
    public boolean available() {
        return keyring() != null;
    }

    @Override
    public String description() {
        Keyring k = keyring();
        if (k == null) {
            return "none available";
        }
        return switch (k.getKeyringStorageType()) {
            case OSX_KEYCHAIN, LEGACY_OSX_KEYCHAIN -> "macOS Keychain";
            case WINDOWS_CREDENTIAL_STORE -> "Windows Credential Manager";
            case GNOME_KEYRING -> "Secret Service (GNOME Keyring)";
            case KWALLET -> "KWallet";
        };
    }

    private Keyring require() {
        Keyring k = keyring();
        if (k == null) {
            throw new SecretStoreException("No secure credential store is available on this computer, so nothing is saved. "
                    + "On Linux, install and unlock a keyring such as GNOME Keyring or KWallet.", null);
        }
        return k;
    }

    @Override
    public void put(String account, String secret) {
        try {
            require().setPassword(SERVICE, account, secret);
        } catch (PasswordAccessException e) {
            throw new SecretStoreException("The credential store refused to save the secret: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> get(String account) {
        try {
            return Optional.ofNullable(require().getPassword(SERVICE, account));
        } catch (PasswordAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String account) {
        try {
            require().deletePassword(SERVICE, account);
        } catch (PasswordAccessException e) {
            // already gone
        }
    }
}
