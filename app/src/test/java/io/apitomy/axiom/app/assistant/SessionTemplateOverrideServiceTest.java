package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.core.entities.SessionTemplateOverrideEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies built-in session template override persistence and merge behaviour.
 */
@QuarkusTest
class SessionTemplateOverrideServiceTest {

    private static final String BUILT_IN = "general-assistant";

    @Inject
    SessionTemplateService service;

    @AfterEach
    @Transactional
    void cleanUp() {
        SessionTemplateOverrideEntity.deleteAll();
        service.listTemplates().stream()
                .filter(t -> !t.builtIn())
                .forEach(t -> service.deleteTemplate(t.templateId()));
    }

    @Test
    void builtInWithoutOverridesHasNoOverriddenFields() {
        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertTrue(t.overriddenFields().isEmpty());
        assertNull(t.model());
    }

    @Test
    void overridesAreMergedIntoGetAndList() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides(
                "my-model", "opencode", "{\"FOO\":\"bar\"}"));

        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertEquals("my-model", t.model());
        assertEquals("opencode", t.engine());
        assertEquals("{\"FOO\":\"bar\"}", t.environment());
        assertEquals(Set.of("model", "engine", "environment"), t.overriddenFields());
        assertTrue(t.builtIn());
        assertTrue(t.systemPrompt().length() > 0);

        SessionTemplateService.SessionTemplate listed = service.listTemplates().stream()
                .filter(x -> x.templateId().equals(BUILT_IN)).findFirst().orElseThrow();
        assertEquals("my-model", listed.model());
    }

    @Test
    void partialOverrideOnlyReportsSetFields() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertEquals(Set.of("model"), t.overriddenFields());
        assertNull(t.engine());
    }

    @Test
    void getOverridesReturnsStoredValuesOrEmpty() {
        SessionTemplateService.TemplateOverrides empty = service.getOverrides(BUILT_IN);
        assertNull(empty.model());
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", "e", null));
        SessionTemplateService.TemplateOverrides o = service.getOverrides(BUILT_IN);
        assertEquals("m", o.model());
        assertEquals("e", o.engine());
    }

    @Test
    void emptySaveDeletesRow() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides(" ", null, "{}"));
        assertEquals(0L, SessionTemplateOverrideEntity.count());
        assertTrue(service.getTemplate(BUILT_IN).overriddenFields().isEmpty());
    }

    @Test
    void clearOverridesRestoresDefaults() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        service.clearOverrides(BUILT_IN);
        assertNull(service.getTemplate(BUILT_IN).model());
        assertEquals(0L, SessionTemplateOverrideEntity.count());
    }

    @Test
    void unknownTemplateIsRejected() {
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.getOverrides("nope"));
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.setOverrides("nope",
                        new SessionTemplateService.TemplateOverrides("m", null, null)));
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.clearOverrides("nope"));
    }

    @Test
    void userTemplateIsRejected() {
        SessionTemplateService.SessionTemplate user = service.createTemplate(
                new SessionTemplateService.SessionTemplate(null, "u", "d", "p", null, null, null,
                        null, null, null, null, null, List.of(), List.of(), false, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.setOverrides(user.templateId(),
                        new SessionTemplateService.TemplateOverrides("m", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.getOverrides(user.templateId()));
        assertThrows(IllegalArgumentException.class, () -> service.clearOverrides(user.templateId()));
    }

    @Test
    @Transactional
    void orphanedOverrideRowIsIgnored() {
        SessionTemplateOverrideEntity orphan = new SessionTemplateOverrideEntity();
        orphan.templateId = "removed-built-in";
        orphan.model = "m";
        orphan.persist();
        assertNull(service.getTemplate("removed-built-in"));
        assertTrue(service.listTemplates().stream()
                .noneMatch(t -> t.templateId().equals("removed-built-in")));
    }
}
