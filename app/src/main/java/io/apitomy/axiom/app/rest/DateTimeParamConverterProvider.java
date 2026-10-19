package io.apitomy.axiom.app.rest;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Converts ISO-8601 date-time strings (e.g. {@code 2026-01-15T00:00:00Z}) to {@link Date} for parameters
 * declared as {@code format: date-time} in the OpenAPI spec. Invalid values are rejected with 400.
 */
@Provider
public class DateTimeParamConverterProvider implements ParamConverterProvider {

    private static final ParamConverter<Date> CONVERTER = new ParamConverter<>() {
        @Override
        public Date fromString(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Date.from(OffsetDateTime.parse(value.trim()).toInstant());
            } catch (DateTimeParseException e) {
                throw new BadRequestException("Invalid date-time (expected ISO-8601, e.g. 2026-01-15T00:00:00Z): "
                        + value);
            }
        }

        @Override
        public String toString(Date value) {
            return value != null ? value.toInstant().toString() : null;
        }
    };

    /**
     * Returns the ISO-8601 converter for {@link Date} parameters, or null for other types.
     *
     * @param rawType     the parameter type
     * @param genericType the generic parameter type
     * @param annotations the parameter annotations
     * @param <T>         the parameter type
     * @return the converter, or null when the type is not {@link Date}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        return rawType == Date.class ? (ParamConverter<T>) CONVERTER : null;
    }
}
