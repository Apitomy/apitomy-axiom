package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.ToolsResource;
import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.api.beans.ToolAiEditRequest;
import io.apitomy.axiom.api.beans.ToolAiEditResponse;
import io.apitomy.axiom.api.beans.ToolDefinition;
import io.apitomy.axiom.api.beans.ToolParameter;
import io.apitomy.axiom.api.beans.ToolSearchResults;
import io.apitomy.axiom.api.beans.ToolTestRequest;
import io.apitomy.axiom.api.beans.ToolTestResponse;
import io.apitomy.axiom.app.ToolAiService;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.services.ToolValidator;
import io.apitomy.axiom.core.services.ToolValidator.ValidationResult;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the Tools REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ToolsResourceImpl implements ToolsResource {

    private static final Logger LOG = Logger.getLogger(ToolsResourceImpl.class);
    private static final int TEST_TIMEOUT_SECONDS = 30;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolAiService toolAiService;

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSearchResults listTools(BigInteger page, BigInteger limit,
                                       String filterName, String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and id in (SELECT t.id FROM ToolDefinitionEntity t"
                    + " JOIN t.labels tl WHERE tl IN :labels"
                    + " GROUP BY t.id HAVING COUNT(DISTINCT tl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        long totalCount = ToolDefinitionEntity.count(hql.toString(), params);
        List<ToolDefinition> items = ToolDefinitionEntity.<ToolDefinitionEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        ToolSearchResults results = new ToolSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    @Override
    @Transactional
    public ToolDefinition createTool(NewToolDefinition data) {
        validateOrThrow(data);
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        applyFields(entity, data);
        entity.persist();
        return toBean(entity);
    }

    @Override
    public ToolDefinition getTool(BigInteger toolId) {
        return toBean(findOrThrow(toolId.longValue()));
    }

    @Override
    @Transactional
    public ToolDefinition updateTool(BigInteger toolId, NewToolDefinition data) {
        validateOrThrow(data);
        ToolDefinitionEntity entity = findOrThrow(toolId.longValue());
        applyFields(entity, data);
        return toBean(entity);
    }

    @Override
    @Transactional
    public void deleteTool(BigInteger toolId) {
        ToolDefinitionEntity entity = findOrThrow(toolId.longValue());
        entity.delete();
    }

    // ── AI-Assisted Editing ────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolAiEditResponse aiEditTool(ToolAiEditRequest data) {
        return toolAiService.editTool(data);
    }

    // ── Tool Validation ───────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.ToolValidationResult validateTool(NewToolDefinition data) {
        ValidationResult result = ToolValidator.validate(data);
        io.apitomy.axiom.api.beans.ToolValidationResult response =
                new io.apitomy.axiom.api.beans.ToolValidationResult();
        response.setValid(!result.hasErrors());
        response.setMessages(result.messages().stream().map(m -> {
            io.apitomy.axiom.api.beans.ToolValidationMessage msg =
                    new io.apitomy.axiom.api.beans.ToolValidationMessage();
            msg.setSeverity(m.severity() == ToolValidator.Severity.ERROR
                    ? io.apitomy.axiom.api.beans.ToolValidationMessage.Severity.ERROR
                    : io.apitomy.axiom.api.beans.ToolValidationMessage.Severity.WARNING);
            msg.setField(m.field());
            msg.setMessage(m.message());
            return msg;
        }).toList());
        return response;
    }

    // ── Tool Testing ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolTestResponse testTool(BigInteger toolId, ToolTestRequest data) {
        ToolDefinitionEntity entity = findOrThrow(toolId.longValue());

        String scriptTemplate = (data.getScriptTemplate() != null && !data.getScriptTemplate().isBlank())
                ? data.getScriptTemplate()
                : entity.scriptTemplate;

        if (scriptTemplate == null || scriptTemplate.isBlank()) {
            throw new WebApplicationException(
                    "Tool has no script template to test", 400);
        }

        String resolvedScript = resolveParameters(scriptTemplate, data);
        Instant startTime = Instant.now();

        try {
            Path scriptFile = Files.createTempFile("axiom-tool-test-", ".sh");
            try {
                Files.writeString(scriptFile, resolvedScript);
                scriptFile.toFile().setExecutable(true);

                LOG.infof("Testing tool '%s' (ID: %d), timeout %ds",
                        entity.name, entity.id, TEST_TIMEOUT_SECONDS);

                ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString())
                        .redirectErrorStream(true);
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                boolean finished = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();

                int exitCode;
                if (!finished) {
                    process.destroyForcibly();
                    exitCode = 1;
                    output = output + "\n[Script timed out after " + TEST_TIMEOUT_SECONDS + "s]";
                } else {
                    exitCode = process.exitValue();
                }

                ToolTestResponse response = new ToolTestResponse();
                response.setSuccess(exitCode == 0);
                response.setExitCode(exitCode);
                response.setOutput(output);
                response.setResolvedScript(resolvedScript);
                response.setDurationMs(durationMs);
                return response;
            } finally {
                Files.deleteIfExists(scriptFile);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Tool test execution failed for '%s'", entity.name);
            ToolTestResponse response = new ToolTestResponse();
            response.setSuccess(false);
            response.setExitCode(1);
            response.setOutput("Execution error: " + e.getMessage());
            response.setResolvedScript(resolvedScript);
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
            response.setDurationMs(durationMs);
            return response;
        }
    }

    private String resolveParameters(String template, ToolTestRequest data) {
        String resolved = template;
        if (data.getParameters() != null) {
            for (Map.Entry<String, Object> entry : data.getParameters().getAdditionalProperties().entrySet()) {
                resolved = resolved.replace("{{" + entry.getKey() + "}}",
                        entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
        }
        return resolved;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void validateOrThrow(NewToolDefinition data) {
        ValidationResult result = ToolValidator.validate(data);
        if (result.hasErrors()) {
            var errors = result.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            var warnings = result.warnings().stream()
                    .map(w -> Map.of("field", w.field(), "message", w.message()))
                    .toList();
            var body = Map.of(
                    "message", "Tool definition has validation errors.",
                    "errors", errors,
                    "warnings", warnings
            );
            throw new WebApplicationException(
                    Response.status(422).entity(body).build());
        }
    }

    private ToolDefinitionEntity findOrThrow(long id) {
        ToolDefinitionEntity entity = ToolDefinitionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Tool not found: " + id, 404);
        }
        return entity;
    }

    private void applyFields(ToolDefinitionEntity entity, NewToolDefinition data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.scriptTemplate = data.getScriptTemplate();

        if (data.getParameters() != null) {
            try {
                entity.parameters = objectMapper.writeValueAsString(data.getParameters());
            } catch (Exception e) {
                entity.parameters = null;
            }
        }
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
    }

    private ToolDefinition toBean(ToolDefinitionEntity entity) {
        ToolDefinition tool = new ToolDefinition();
        tool.setId(entity.id);
        tool.setName(entity.name);
        tool.setDescription(entity.description);
        tool.setScriptTemplate(entity.scriptTemplate);

        if (entity.parameters != null) {
            try {
                List<ToolParameter> params = objectMapper.readValue(entity.parameters,
                        new TypeReference<List<ToolParameter>>() {});
                tool.setParameters(params);
            } catch (Exception e) {
                // ignore
            }
        }

        tool.setLabels(entity.labels);

        return tool;
    }
}
