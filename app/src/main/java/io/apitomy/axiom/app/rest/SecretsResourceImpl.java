package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.SecretsResource;
import io.apitomy.axiom.api.beans.NewSecret;
import io.apitomy.axiom.api.beans.Secret;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.SecretExpiry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Secrets REST API. Provides CRUD operations
 * for encrypted secrets that are injected into agent subprocess
 * environments. Secret values are never returned in API responses.
 */
@ApplicationScoped
@RunOnVirtualThread
public class SecretsResourceImpl implements SecretsResource {

    @Inject
    EncryptionService encryptionService;

    @Inject
    Event<SseEvent> sseEvents;

    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int defaultWarnDays;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Secret> listSecrets() {
        Instant now = Instant.now();
        return SecretEntity.<SecretEntity>listAll()
                .stream().map(entity -> toBean(entity, now)).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret createSecret(NewSecret data) {
        Instant now = Instant.now();
        validateExpiry(data, now);
        SecretEntity entity = new SecretEntity();
        apply(entity, data);
        entity.persist();
        Secret bean = toBean(entity, now);
        sseEvents.fire(SseEvent.secretExpiry(entity.id, bean.getExpiryStatus().toString()));
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret updateSecret(long secretId, NewSecret data) {
        Instant now = Instant.now();
        SecretEntity entity = findOrThrow(secretId);
        validateExpiry(data, now);
        apply(entity, data);
        Secret bean = toBean(entity, now);
        sseEvents.fire(SseEvent.secretExpiry(entity.id, bean.getExpiryStatus().toString()));
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSecret(long secretId) {
        SecretEntity entity = findOrThrow(secretId);
        entity.delete();
        sseEvents.fire(SseEvent.secretExpiry(secretId, null));
    }

    /**
     * Copies request data onto the entity. Every create/update carries a new value (a rotation), so the
     * expiry event bookkeeping is reset.
     */
    private void apply(SecretEntity entity, NewSecret data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.encryptedValue = encryptionService.encrypt(data.getValue());
        entity.expiresOn = data.getExpiresOn() != null ? data.getExpiresOn().toInstant() : null;
        entity.warnDaysBefore = data.getWarnDaysBefore();
        entity.expiryWarningSentOn = null;
        entity.expiredEventSentOn = null;
    }

    /**
     * Builds a 400 Bad Request exception carrying a JSON body of the form {@code {"message": "..."}}.
     *
     * @param message the human-readable validation message
     * @return the exception to throw
     */
    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", message))
                .build());
    }

    private static void validateExpiry(NewSecret data, Instant now) {
        if (data.getExpiresOn() != null && !data.getExpiresOn().toInstant().isAfter(now)) {
            throw badRequest("expiresOn must be in the future");
        }
        if (data.getWarnDaysBefore() != null && data.getWarnDaysBefore() < 0) {
            throw badRequest("warnDaysBefore must not be negative");
        }
    }

    private SecretEntity findOrThrow(long id) {
        SecretEntity entity = SecretEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Secret not found: " + id, 404);
        }
        return entity;
    }

    private Secret toBean(SecretEntity entity, Instant now) {
        Secret secret = new Secret();
        secret.setId(entity.id);
        secret.setName(entity.name);
        secret.setDescription(entity.description);
        secret.setExpiresOn(entity.expiresOn != null ? Date.from(entity.expiresOn) : null);
        secret.setWarnDaysBefore(entity.warnDaysBefore);
        SecretExpiry.Status status = SecretExpiry.status(entity.expiresOn, entity.warnDaysBefore,
                defaultWarnDays, now);
        secret.setExpiryStatus(Secret.ExpiryStatus.valueOf(status.name()));
        return secret;
    }
}
