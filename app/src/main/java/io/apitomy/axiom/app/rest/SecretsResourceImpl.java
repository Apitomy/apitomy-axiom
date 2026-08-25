package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.SecretsResource;
import io.apitomy.axiom.api.beans.NewSecret;
import io.apitomy.axiom.api.beans.Secret;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;

import java.util.List;

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

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Secret> listSecrets() {
        return SecretEntity.<SecretEntity>listAll()
                .stream().map(this::toBean).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret createSecret(NewSecret data) {
        SecretEntity entity = new SecretEntity();
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.encryptedValue = encryptionService.encrypt(data.getValue());
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Secret updateSecret(long secretId, NewSecret data) {
        SecretEntity entity = findOrThrow(secretId);
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.encryptedValue = encryptionService.encrypt(data.getValue());
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSecret(long secretId) {
        SecretEntity entity = findOrThrow(secretId);
        entity.delete();
    }

    private SecretEntity findOrThrow(long id) {
        SecretEntity entity = SecretEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Secret not found: " + id, 404);
        }
        return entity;
    }

    private Secret toBean(SecretEntity entity) {
        Secret secret = new Secret();
        secret.setId(entity.id);
        secret.setName(entity.name);
        secret.setDescription(entity.description);
        return secret;
    }
}
