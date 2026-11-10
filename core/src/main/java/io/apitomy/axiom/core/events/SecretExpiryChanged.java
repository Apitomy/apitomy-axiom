package io.apitomy.axiom.core.events;

/**
 * CDI event fired in the transaction that changed a secret's expiry state (create, update, delete, or an
 * expiry event claimed by the checker). Observed after a successful commit by
 * {@link SecretExpirySseNotifier}, which notifies SSE clients. Never carries the secret value.
 *
 * @param secretId the secret id
 * @param status   the expiry status name, or {@code null} when the secret was deleted
 */
public record SecretExpiryChanged(Long secretId, String status) {
}
