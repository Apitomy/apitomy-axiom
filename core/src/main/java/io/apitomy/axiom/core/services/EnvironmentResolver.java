package io.apitomy.axiom.core.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SecretEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves environment variable maps from JSON configuration strings.
 * Values containing {@code ${secret:NAME}} references are resolved by
 * looking up the named secret and decrypting its value.
 *
 * <p>Secrets are never injected automatically: a process gets only the variables its environment
 * declares. A reference to a secret that does not exist, or cannot be decrypted, throws
 * {@link MissingSecretException} so the process does not start with a wrong value.</p>
 */
@ApplicationScoped
public class EnvironmentResolver {

    private static final Logger LOG = Logger.getLogger(EnvironmentResolver.class);
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([^}]+)}");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EncryptionService encryptionService;

    /**
     * Returns true if the given environment JSON is non-null, non-blank,
     * and contains at least one entry.
     */
    public boolean hasCustomEnvironment(String environmentJson) {
        if (environmentJson == null || environmentJson.isBlank()) {
            return false;
        }
        try {
            Map<String, String> env = objectMapper.readValue(environmentJson,
                    new TypeReference<>() {});
            return !env.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses the environment JSON and resolves any {@code ${secret:NAME}}
     * references by looking up and decrypting the named secrets.
     *
     * @param environmentJson JSON object string with env var key-value pairs
     * @return the resolved environment map
     * @throws MissingSecretException when a referenced secret does not exist or cannot be decrypted
     */
    public Map<String, String> resolve(String environmentJson) {
        if (environmentJson == null || environmentJson.isBlank()) {
            return Map.of();
        }

        try {
            Map<String, String> raw = objectMapper.readValue(environmentJson,
                    new TypeReference<>() {});
            Map<String, String> resolved = new HashMap<>();

            for (Map.Entry<String, String> entry : raw.entrySet()) {
                resolved.put(entry.getKey(), resolveValue(entry.getValue()));
            }

            return resolved;
        } catch (MissingSecretException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse environment JSON — skipping custom environment");
            return Map.of();
        }
    }

    /**
     * Resolves a single value, replacing any {@code ${secret:NAME}} references
     * with the decrypted secret values.
     */
    private String resolveValue(String value) {
        if (value == null) return "";

        Matcher matcher = SECRET_REF.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String secretName = matcher.group(1);
            String replacement = lookupSecret(secretName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String lookupSecret(String secretName) {
        SecretEntity secret = SecretEntity.find("name", secretName).firstResult();
        if (secret == null) {
            throw new MissingSecretException("Secret '" + secretName + "' does not exist");
        }
        try {
            return encryptionService.decrypt(secret.encryptedValue);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to decrypt secret '%s'", secretName);
            throw new MissingSecretException("Secret '" + secretName + "' could not be decrypted");
        }
    }
}
