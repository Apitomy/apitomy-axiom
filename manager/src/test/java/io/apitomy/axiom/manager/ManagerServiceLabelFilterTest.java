package io.apitomy.axiom.manager;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ManagerService's label-based action type filtering logic.
 */
class ManagerServiceLabelFilterTest {

    @Test
    void testNoLabelsOnActionTypeAlwaysIncluded() {
        ActionTypeEntity at = makeActionType("deploy", List.of());
        List<ActionTypeEntity> result = ManagerService.filterByLabels(List.of(at), List.of("team-a"));
        assertEquals(1, result.size());
        assertEquals("deploy", result.get(0).name);
    }

    @Test
    void testNullLabelsOnActionTypeAlwaysIncluded() {
        ActionTypeEntity at = makeActionType("deploy", null);
        List<ActionTypeEntity> result = ManagerService.filterByLabels(List.of(at), List.of("team-a"));
        assertEquals(1, result.size());
    }

    @Test
    void testNoLabelsOnActionTypeNoLabelsOnEvent() {
        ActionTypeEntity at = makeActionType("deploy", List.of());
        List<ActionTypeEntity> result = ManagerService.filterByLabels(List.of(at), Collections.emptyList());
        assertEquals(1, result.size());
    }

    @Test
    void testActionTypeLabelsSubsetOfEventLabels() {
        ActionTypeEntity at = makeActionType("deploy", List.of("team-a"));
        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                List.of(at), List.of("team-a", "team-b", "prod"));
        assertEquals(1, result.size());
        assertEquals("deploy", result.get(0).name);
    }

    @Test
    void testActionTypeLabelsExactMatchEventLabels() {
        ActionTypeEntity at = makeActionType("deploy", List.of("team-a", "prod"));
        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                List.of(at), List.of("team-a", "prod"));
        assertEquals(1, result.size());
    }

    @Test
    void testActionTypeLabelsNotSubsetExcluded() {
        ActionTypeEntity at = makeActionType("deploy", List.of("team-a", "prod"));
        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                List.of(at), List.of("team-b"));
        assertTrue(result.isEmpty());
    }

    @Test
    void testActionTypeLabelsNoEventLabelsExcluded() {
        ActionTypeEntity at = makeActionType("deploy", List.of("team-a"));
        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                List.of(at), Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    void testMixedActionTypesFilteredCorrectly() {
        ActionTypeEntity noLabels = makeActionType("analyze", List.of());
        ActionTypeEntity matching = makeActionType("deploy", List.of("team-a"));
        ActionTypeEntity nonMatching = makeActionType("review", List.of("team-b"));

        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                List.of(noLabels, matching, nonMatching), List.of("team-a", "prod"));

        assertEquals(2, result.size());
        assertEquals("analyze", result.get(0).name);
        assertEquals("deploy", result.get(1).name);
    }

    @Test
    void testEmptyActionTypeListReturnsEmpty() {
        List<ActionTypeEntity> result = ManagerService.filterByLabels(
                Collections.emptyList(), List.of("team-a"));
        assertTrue(result.isEmpty());
    }

    @Test
    void testNullActionTypeListReturnsNull() {
        List<ActionTypeEntity> result = ManagerService.filterByLabels(null, List.of("team-a"));
        assertNull(result);
    }

    private ActionTypeEntity makeActionType(String name, List<String> labels) {
        ActionTypeEntity entity = new ActionTypeEntity();
        entity.name = name;
        entity.labels = labels != null ? new ArrayList<>(labels) : null;
        return entity;
    }
}
