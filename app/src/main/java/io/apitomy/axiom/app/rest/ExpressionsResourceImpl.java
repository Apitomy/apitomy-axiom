package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.ExpressionsResource;
import io.apitomy.axiom.api.beans.ExpressionEvaluateRequest;
import io.apitomy.axiom.api.beans.ExpressionEvaluateResult;
import io.apitomy.axiom.app.expressions.ExpressionService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/**
 * REST resource that evaluates EL expressions per workflow authoring scope.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ExpressionsResourceImpl implements ExpressionsResource {

    @Inject
    ExpressionService expressions;

    @Inject
    ObjectMapper mapper;

    /**
     * Evaluates an expression in a scope.
     *
     * @param data the expression, scope, optional event ID and context
     * @return the evaluation result
     */
    @Override
    public ExpressionEvaluateResult evaluateExpression(ExpressionEvaluateRequest data) {
        String scope = data.getScope() != null ? data.getScope().value() : null;
        JsonNode context = data.getContext() != null
                ? mapper.valueToTree(data.getContext().getAdditionalProperties()) : null;
        ExpressionService.Result result = expressions.evaluate(data.getExpression(), scope, data.getEventId(),
                context);
        ExpressionEvaluateResult bean = new ExpressionEvaluateResult();
        bean.setOk(result.ok());
        bean.setError(result.error());
        if (result.ok() && result.value() != null) {
            try {
                bean.setValueJson(mapper.writeValueAsString(result.value()));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            bean.setValueType(valueType(result.value()));
        }
        return bean;
    }

    private static ExpressionEvaluateResult.ValueType valueType(JsonNode node) {
        return switch (node.getNodeType()) {
            case BOOLEAN -> ExpressionEvaluateResult.ValueType.BOOLEAN;
            case NUMBER -> ExpressionEvaluateResult.ValueType.NUMBER;
            case STRING, BINARY -> ExpressionEvaluateResult.ValueType.STRING;
            case OBJECT, POJO -> ExpressionEvaluateResult.ValueType.OBJECT;
            case ARRAY -> ExpressionEvaluateResult.ValueType.ARRAY;
            default -> ExpressionEvaluateResult.ValueType.NULL;
        };
    }

    /**
     * Maps a blank expression or an unknown scope to 400.
     *
     * @param e the failure
     * @return a 400 response with a {@code message}
     */
    @ServerExceptionMapper
    public Response mapIllegalArgument(IllegalArgumentException e) {
        return Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", String.valueOf(e.getMessage()))).build();
    }
}
