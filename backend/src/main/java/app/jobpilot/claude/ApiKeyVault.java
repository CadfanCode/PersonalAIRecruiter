package app.jobpilot.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import app.jobpilot.credentials.SecretStore;
import app.jobpilot.credentials.SecretStore.SecretStoreException;
import app.jobpilot.settings.JobPilotProperties;

/**
 * Where the Gemini API key lives: the OS credential store when there is one, otherwise an AES-GCM encrypted file in the data
 * directory whose random key sits next to it with owner-only permissions. The fallback keeps the key out of plain text and out
 * of the database, but it only guards against other users and casual reading, not against malware running as this user.
 * Saved portal passwords never use this fallback: they stay in the OS credential store only (CLAUDE.md rule 2).
 */
@Component
public class ApiKeyVault {

    public static final String ACCOUNT = "gemini-api-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretStore store;
    private final Path dir;

    public ApiKeyVault(SecretStore store, JobPilotProperties props) {
        this.store = store;
        this.dir = props.dataDir().resolve("secrets");
    }

    public void put(String key) {
        if (store.available()) {
            store.put(ACCOUNT, key);
            return;
        }
        try {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, fileKey(true), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            Path file = dir.resolve("gemini.enc");
            Files.writeString(file, Base64.getEncoder().encodeToString(out));
            restrict(file, "rw-------");
        } catch (IOException | GeneralSecurityException e) {
            throw new SecretStoreException("The key could not be saved: " + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<String> get() {
        if (store.available()) {
            return store.get(ACCOUNT);
        }
        Path file = dir.resolve("gemini.enc");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            byte[] in = Base64.getDecoder().decode(Files.readString(file).trim());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, fileKey(false), new GCMParameterSpec(128, in, 0, 12));
            return Optional.of(new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretStoreException("The saved key could not be read. Save it again under Settings.", e);
        }
    }

    public void delete() {
        if (store.available()) {
            store.delete(ACCOUNT);
        }
        try {
            Files.deleteIfExists(dir.resolve("gemini.enc"));
        } catch (IOException e) {
            throw new SecretStoreException("The saved key could not be removed", e);
        }
    }

    /** For the UI: where the key is kept. */
    public String description() {
        return store.available() ? store.description() : "encrypted file in the JobPilot data folder";
    }

    private SecretKeySpec fileKey(boolean create) throws IOException {
        Path keyFile = dir.resolve(".key");
        if (!Files.exists(keyFile)) {
            if (!create) {
                throw new IOException("key file missing");
            }
            byte[] fresh = new byte[32];
            RANDOM.nextBytes(fresh);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh));
            restrict(keyFile, "rw-------");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim()), "AES");
    }

    private static void restrict(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows): the user profile directory is already private
        }
    }
}
