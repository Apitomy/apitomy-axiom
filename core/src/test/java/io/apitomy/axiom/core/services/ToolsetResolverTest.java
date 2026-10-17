package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ToolsetEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ToolsetResolver} using a stubbed toolset lookup.
 */
class ToolsetResolverTest {

    private final ToolsetResolver resolver = new ToolsetResolver();

    @Test
    void nullBlankAndDuplicateEntriesAreIgnored() {
        List<String> resolved = resolver.resolve(Arrays.asList("Read", null, " ", "Read"));

        assertEquals(List.of("Read"), resolved);
    }

    @Test
    void nullOrEmptyListResolvesToEmpty() {
        assertEquals(List.of(), resolver.resolve((List<String>) null));
        assertEquals(List.of(), resolver.resolve(List.of()));
    }

    @Test
    void toolsetWithNullToolsIsSkipped() {
        ToolsetResolver stubbed = stub(Map.of("Empty", toolset("Empty", null)));

        assertEquals(List.of("Bash"), stubbed.resolve(List.of("@Empty", "Bash")));
    }

    @Test
    void diamondToolsetsResolveOnceInOrder() {
        ToolsetResolver stubbed = stub(Map.of(
                "Base", toolset("Base", "Read, Grep"),
                "Left", toolset("Left", "@Base, Edit"),
                "Right", toolset("Right", "@Base, Write"),
                "Top", toolset("Top", "@Left, @Right")));

        assertEquals(List.of("Read", "Grep", "Edit", "Write"), stubbed.resolve(List.of("@Top")));
    }

    private static ToolsetEntity toolset(String name, String tools) {
        ToolsetEntity entity = new ToolsetEntity();
        entity.name = name;
        entity.tools = tools;
        return entity;
    }

    private static ToolsetResolver stub(Map<String, ToolsetEntity> toolsets) {
        return new ToolsetResolver() {
            @Override
            ToolsetEntity findToolset(String name) {
                return toolsets.get(name);
            }
        };
    }
}
