package io.apicurio.axiom.engine.bob;

/**
 * Parsed result from an IBM Bob CLI invocation.
 */
public record BobResult(
        String result,
        int exitCode
) {

    public static BobResult failed(String errorMessage, int exitCode) {
        return new BobResult(errorMessage, exitCode);
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
