package io.apitomy.axiom.app;

import java.util.UUID;

/**
 * The stream event (and processing ledger entry) that caused a workflow action: starting a run
 * (create-workflow routing) or resuming one (workflow-dispatch routing).
 *
 * @param eventId  the stream event ID
 * @param ledgerId the ledger entry (event × subscription) being routed; may be null
 */
public record EventOrigin(UUID eventId, Long ledgerId) {
}
