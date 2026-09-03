package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

import java.io.IOException;

/**
 * Normalizes generated-enum deserialization failures so they can be reported with an
 * informative HTTP 400 body.
 *
 * <p>The generated API beans read enum values through a {@code @JsonCreator fromValue(...)}
 * factory that throws {@link IllegalArgumentException} for out-of-range input. Jackson wraps
 * that in a {@code ValueInstantiationException}, which Quarkus REST (RESTEasy Reactive)
 * converts into a bodyless {@code 400} <em>before</em> any {@code ExceptionMapper} runs —
 * only {@code MismatchedInputException}s survive to the mapper layer. This customizer installs
 * a {@link DeserializationProblemHandler} that rethrows enum instantiation failures as an
 * {@link InvalidFormatException} (a {@code MismatchedInputException}), allowing
 * {@link InvalidFormatExceptionMapper} to produce an explanatory response.</p>
 */
@Singleton
public class EnumDeserializationCustomizer implements ObjectMapperCustomizer {

    /**
     * Registers the enum-normalizing {@link DeserializationProblemHandler} on the mapper.
     *
     * @param objectMapper the application {@link ObjectMapper} being configured
     */
    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public Object handleInstantiationProblem(DeserializationContext ctxt, Class<?> instClass,
                    Object argument, Throwable t) throws IOException {
                if (instClass != null && instClass.isEnum() && t instanceof IllegalArgumentException) {
                    JsonParser parser = ctxt.getParser();
                    throw InvalidFormatException.from(parser,
                            "Value '" + argument + "' is not a valid " + instClass.getSimpleName(),
                            argument, instClass);
                }
                return super.handleInstantiationProblem(ctxt, instClass, argument, t);
            }
        });
    }
}
