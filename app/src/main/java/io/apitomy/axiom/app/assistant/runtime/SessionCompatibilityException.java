package io.apitomy.axiom.app.assistant.runtime;

import java.util.Map;

/**
 * Indicates that an interactive runtime is not compatible with Assistant session requirements.
 */
public class SessionCompatibilityException extends RuntimeException {

    public static final String RUNTIME_UNHEALTHY = "RUNTIME_UNHEALTHY";
    public static final String SESSION_PROTOCOL_UNSUPPORTED = "SESSION_PROTOCOL_UNSUPPORTED";
    public static final String EVENT_STREAM_UNRELIABLE = "EVENT_STREAM_UNRELIABLE";
    public static final String PROMPT_PROTOCOL_UNSUPPORTED = "PROMPT_PROTOCOL_UNSUPPORTED";
    public static final String PERMISSION_PROTOCOL_UNSUPPORTED = "PERMISSION_PROTOCOL_UNSUPPORTED";
    public static final String INTERRUPT_PROTOCOL_UNSUPPORTED = "INTERRUPT_PROTOCOL_UNSUPPORTED";

    private final String code;
    private final Map<String, String> details;

    /**
     * Creates a compatibility exception with a code and message.
     *
     * @param code compatibility failure code
     * @param message user-facing failure message
     */
    public SessionCompatibilityException(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * Creates a compatibility exception with a code, message, and details.
     *
     * @param code compatibility failure code
     * @param message user-facing failure message
     * @param details structured diagnostic details
     */
    public SessionCompatibilityException(String code, String message,
                                         Map<String, String> details) {
        super(message);
        this.code = code;
        this.details = details != null ? Map.copyOf(details) : Map.of();
    }

    /**
     * Returns the compatibility failure code.
     *
     * @return compatibility code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns diagnostic details for compatibility failures.
     *
     * @return immutable diagnostic details map
     */
    public Map<String, String> getDetails() {
        return details;
    }
}
