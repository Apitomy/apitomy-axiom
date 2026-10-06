package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.ValidationSeverity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Publishes workflow definition drafts; used by the REST publish endpoint and by configuration import. */
@ApplicationScoped
public class WorkflowPublisher {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Validates the draft content of a definition and creates the next published version.
     * Throws {@link WebApplicationException} (400) when there is no draft, the JSON is invalid, the flow
     * engine reports errors, or a Start input declaration is invalid.
     *
     * @param entity the definition (managed entity)
     * @return the new version entity
     */
    @Transactional
    public WorkflowDefinitionVersionEntity publish(WorkflowDefinitionEntity entity) {
        if (entity.content == null || entity.content.isBlank()) {
            throw new WebApplicationException("No draft content to publish", 400);
        }

        Workflow workflow;
        try {
            workflow = objectMapper.readValue(entity.content, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Invalid workflow JSON: " + e.getMessage(), 400);
        }

        List<ValidationProblem> errors = WorkflowContentRules.engineProblems(workflow).stream()
                .filter(p -> p.severity() == ValidationSeverity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new WebApplicationException(Response.status(400).entity(errors).build());
        }

        Optional<String> violation = WorkflowContentRules.startInputViolation(workflow);
        if (violation.isPresent()) {
            throw new WebApplicationException(
                    Response.status(400).entity(Map.of("message", violation.get())).build());
        }

        int newVersion = entity.currentVersion != null ? entity.currentVersion + 1 : 1;

        WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
        version.definitionId = entity.id;
        version.version = newVersion;
        version.content = entity.content;
        version.createdOn = Instant.now();
        version.persist();

        entity.currentVersion = newVersion;
        entity.updatedOn = Instant.now();
        return version;
    }
}
