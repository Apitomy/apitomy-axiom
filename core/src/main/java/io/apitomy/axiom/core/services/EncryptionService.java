package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.config.AxiomHome;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Provides AES-256-GCM encryption and decryption for secrets stored in
 * the database. The encryption key is auto-generated on first startup
 * and stored in {@code ~/.axiom/secret.key}.
 *
 * <p>The key is loaded when Axiom starts. If it cannot be read or created, or is not a valid 256-bit key, the
 * problem is logged and startup fails, so Axiom exits instead of running without usable secrets.</p>
 */
@Startup
@ApplicationScoped
public class EncryptionService {

    private static final Logger LOG = Logger.getLogger(EncryptionService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 256;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        Path keyFile = AxiomHome.resolve("secret.key");
        try {
            if (Files.exists(keyFile)) {
                byte[] keyBytes = Files.readAllBytes(keyFile);
                if (keyBytes.length != KEY_LENGTH / 8) {
                    String message = "The encryption key in " + keyFile + " is not a valid 256-bit key ("
                            + keyBytes.length + " bytes instead of " + (KEY_LENGTH / 8) + "). Restore the key "
                            + "from a backup; without it, stored secrets cannot be decrypted.";
                    LOG.error(message);
                    throw new IllegalStateException(message);
                }
                secretKey = new SecretKeySpec(keyBytes, "AES");
                LOG.debug("Loaded encryption key from " + keyFile);
            } else {
                Files.createDirectories(keyFile.getParent());
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(KEY_LENGTH);
                secretKey = keyGen.generateKey();
                Files.write(keyFile, secretKey.getEncoded());
                try {
                    Files.setPosixFilePermissions(keyFile,
                            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException e) {
                    // Windows — no POSIX permissions
                }
                LOG.infof("Generated new encryption key at %s", keyFile);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String message = "Cannot load the encryption key from " + keyFile + ": " + e;
            LOG.error(message, e);
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * Encrypts a plaintext string using AES-256-GCM.
     *
     * @param plaintext the value to encrypt
     * @return Base64-encoded string containing IV + ciphertext
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded string produced by {@link #encrypt(String)}.
     *
     * @param encrypted the Base64 string containing IV + ciphertext
     * @return the original plaintext
     */
    public String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
