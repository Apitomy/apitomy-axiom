package io.apitomy.axiom.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SdkFunctionRegistry {

    private static final String MCP_PREFIX = "mcp__axiom-sdk__";
    private static final List<SdkFunction> FUNCTIONS;
    private static final Set<String> ALL_TOOL_NAMES;
    private static final String ALL_TOOL_NAMES_CSV;
    private static final Set<String> SDK_CALL_NAMES;

    static {
        try (InputStream is = SdkFunctionRegistry.class.getClassLoader()
                .getResourceAsStream("axiom-sdk-functions.json")) {
            if (is == null) {
                throw new IllegalStateException("axiom-sdk-functions.json not found on classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            FUNCTIONS = mapper.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        ALL_TOOL_NAMES = FUNCTIONS.stream()
                .map(f -> MCP_PREFIX + f.name())
                .collect(Collectors.toUnmodifiableSet());
        ALL_TOOL_NAMES_CSV = String.join(",", ALL_TOOL_NAMES);
        SDK_CALL_NAMES = FUNCTIONS.stream()
                .filter(SdkFunction::sdkCallSupported)
                .map(SdkFunction::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static List<SdkFunction> all() {
        return FUNCTIONS;
    }

    public static List<SdkFunction> sdkCallFunctions() {
        return FUNCTIONS.stream()
                .filter(SdkFunction::sdkCallSupported)
                .toList();
    }

    public static Set<String> allToolNames() {
        return ALL_TOOL_NAMES;
    }

    public static String allToolNamesCsv() {
        return ALL_TOOL_NAMES_CSV;
    }

    public static boolean isValidSdkCall(String functionName) {
        return SDK_CALL_NAMES.contains(functionName);
    }

    public static String mcpPrefix() {
        return MCP_PREFIX;
    }

    private SdkFunctionRegistry() {
    }
}
