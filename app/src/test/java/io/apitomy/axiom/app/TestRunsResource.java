package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.TriggerWorkflow;
import io.apitomy.axiom.api.beans.WorkflowInstance;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only REST endpoint that starts a workflow run directly on a subject, or reads its latest run. The public
 * API no longer offers this (runs start from stream events, subscriptions, schedules and re-runs); tests use it
 * to drive the workflow engine over HTTP. It lives in the test sources and is not part of the application.
 */
@Path(TestRunsResource.BASE + "/{subjectId}/workflow")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestRunsResource {

    /** Base path of the test-only subject endpoints. */
    public static final String BASE = "/api/v1/test-support/subjects";

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    /**
     * Returns the path that starts, or reads the latest, workflow run of a subject.
     *
     * @param subjectId the subject id
     * @return the endpoint path
     */
    public static String path(Object subjectId) {
        return BASE + "/" + subjectId + "/workflow";
    }

    /**
     * Starts a run of a published workflow definition on the subject.
     *
     * @param subjectId the subject id
     * @param data the definition id and optional title and labels
     * @return the started run
     */
    @POST
    @Transactional
    public WorkflowInstance start(@PathParam("subjectId") long subjectId, TriggerWorkflow data) {
        if (data == null || data.getWorkflowDefinitionId() == null) {
            throw new WebApplicationException("Missing required 'workflowDefinitionId' field", 400);
        }
        Map<String, String> labels = null;
        if (data.getLabels() != null && data.getLabels().getAdditionalProperties() != null) {
            labels = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : data.getLabels().getAdditionalProperties().entrySet()) {
                labels.put(e.getKey(), e.getValue() != null ? String.valueOf(e.getValue()) : null);
            }
        }
        WorkflowRunEntity run = workflowExecutionService.triggerWorkflow(subjectId, data.getWorkflowDefinitionId(),
                new RunRequest(data.getTitle(), labels), null);
        return runBeanMapper.toBean(run);
    }

    /**
     * Returns the subject's most recently started run.
     *
     * @param subjectId the subject id
     * @return the run
     */
    @GET
    @Transactional
    public WorkflowInstance latest(@PathParam("subjectId") long subjectId) {
        WorkflowRunEntity run = WorkflowRunEntity.find("subjectId", Sort.descending("startedOn"), subjectId)
                .firstResult();
        if (run == null) {
            throw new WebApplicationException(404);
        }
        return runBeanMapper.toBean(run);
    }
}
