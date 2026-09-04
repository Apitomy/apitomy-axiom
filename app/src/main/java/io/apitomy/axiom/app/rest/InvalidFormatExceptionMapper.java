package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates Jackson {@link InvalidFormatException}s (raised while deserializing a
 * request body) into an informative HTTP 400 response instead of the empty body
 * produced by the default handler.
 *
 * <p>The most common trigger is an enum-typed property receiving a value outside
 * the allowed set — for example an action type field {@code type} of
 * {@code "integer"} when only {@code string|number|boolean|object} are permitted.
 * Without this mapper the client receives a bare 400 with no explanation.</p>
 *
 * <p>Enum values in the generated beans are read through a {@code @JsonCreator}
 * factory that throws {@link IllegalArgumentException}, which Jackson would otherwise
 * wrap as a {@code ValueInstantiationException} — a type Quarkus REST converts into a
 * bodyless 400 before any {@code ExceptionMapper} runs. {@link EnumDeserializationCustomizer}
 * normalizes those failures into an {@link InvalidFormatException} so that this mapper
 * can handle them uniformly.</p>
 */
@Provider
public class InvalidFormatExceptionMapper implements ExceptionMapper<InvalidFormatException> {

    /**
     * Builds a {@code 400 Bad Request} response whose JSON body carries a human-readable
     * {@code message} describing the offending value, the field it was bound to, and (for
     * enum targets) the set of allowed values.
     *
     * @param exception the format failure raised while reading the request body
     * @return a {@code 400 Bad Request} response with an explanatory JSON body
     */
    @Override
    public Response toResponse(InvalidFormatException exception) {
        String field = pathOf(exception);
        Object value = exception.getValue();
        Class<?> targetType = exception.getTargetType();

        StringBuilder message = new StringBuilder("Invalid value ");
        message.append("'").append(value).append("'");
        if (field != null && !field.isBlank()) {
            message.append(" for field '").append(field).append("'");
        }
        message.append(".");

        List<String> allowed = allowedEnumValues(targetType);
        if (!allowed.isEmpty()) {
            message.append(" Allowed values: ").append(String.join(", ", allowed)).append(".");
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", message.toString()))
                .build();
    }

    /**
     * Builds a dotted JSON path (e.g. {@code inputs[0].type}) from the exception's
     * path references, or {@code null} if none are available.
     *
     * @param exception the format failure
     * @return the dotted path to the offending property, or {@code null}
     */
    private static String pathOf(InvalidFormatException exception) {
        List<JsonMappingException.Reference> path = exception.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : path) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append(".");
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append("[").append(ref.getIndex()).append("]");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Returns the JSON values allowed for an enum target type, using the constant's
     * {@code toString()} (which the generated enums override to return the JSON value).
     * Returns an empty list for non-enum types.
     *
     * @param targetType the type the value was being bound to
     * @return the allowed JSON values, or an empty list when {@code targetType} is not an enum
     */
    private static List<String> allowedEnumValues(Class<?> targetType) {
        if (targetType == null || !targetType.isEnum()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object constant : targetType.getEnumConstants()) {
            values.add(String.valueOf(constant));
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).collect(Collectors.toList());
    }
}
