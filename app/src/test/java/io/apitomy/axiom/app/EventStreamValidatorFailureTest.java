package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.PersistOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventStreamValidatorFailureTest {

    @Inject
    EventStreamService service;

    @InjectMock
    EventSchemaRegistry registry;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.delete("eventSourceId", "val-x"));
    }

    @Test
    void validatorExceptionIsRecordedAndEventSaved() {
        Mockito.when(registry.validate(Mockito.any(NormalizedEvent.class)))
                .thenThrow(new IllegalStateException("kaboom"));
        NormalizedEvent event = EventStreamValidationTest.event("val-x", "P-9-1", "2026-10-21T14:53:07Z");
        assertEquals(PersistOutcome.PERSISTED_INVALID, service.persistEvent(event));
        String errors = EventStreamValidationTest.stored("val-x", "P-9-1").validationErrors;
        assertTrue(errors.contains("kaboom"), errors);
    }
}
