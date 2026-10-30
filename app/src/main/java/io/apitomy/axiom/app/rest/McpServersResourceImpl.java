package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.McpResource;
import io.apitomy.axiom.api.beans.Headers;
import io.apitomy.axiom.api.beans.McpServer;
import io.apitomy.axiom.api.beans.McpServerInUseError;
import io.apitomy.axiom.api.beans.McpServerTestResult;
import io.apitomy.axiom.api.beans.McpServerTool;
import io.apitomy.axiom.api.beans.McpServerUsage;
import io.apitomy.axiom.api.beans.NewMcpServer;
import io.apitomy.axiom.api.beans.ServerEnv;
import io.apitomy.axiom.app.mcp.McpConnectionTester;
import io.apitomy.axiom.app.mcp.McpServerConflictException;
import io.apitomy.axiom.app.mcp.McpServerInUseException;
import io.apitomy.axiom.app.mcp.McpServerReferences;
import io.apitomy.axiom.app.mcp.McpServerNotFoundException;
import io.apitomy.axiom.app.mcp.McpServerService;
import io.apitomy.axiom.app.mcp.McpServerValidationException;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the MCP Servers REST API. Delegates to {@link McpServerService}.
 */
@ApplicationScoped
@RunOnVirtualThread
public class McpServersResourceImpl implements McpResource {

    private static final Logger LOG = Logger.getLogger(McpServersResourceImpl.class);

    @Inject
    McpServerService service;

    @Inject
    ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<McpServer> listMcpServers() {
        return service.list().stream().map(this::toBean).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public McpServer createMcpServer(NewMcpServer data) {
        return toBean(service.create(data));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public McpServer getMcpServer(long mcpServerId) {
        return toBean(service.get(mcpServerId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public McpServer updateMcpServer(long mcpServerId, NewMcpServer data) {
        return toBean(service.update(mcpServerId, data));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteMcpServer(long mcpServerId) {
        service.delete(mcpServerId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<McpServerUsage> getMcpServerUsage(long mcpServerId) {
        return service.usages(mcpServerId).stream().map(McpServersResourceImpl::toUsageBean).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public McpServerTestResult testMcpServer(long mcpServerId) {
        return toResultBean(service.test(mcpServerId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public McpServerTestResult testNewMcpServer(NewMcpServer data) {
        return toResultBean(service.test(data));
    }

    private static McpServerTestResult toResultBean(McpConnectionTester.Result result) {
        McpServerTestResult bean = new McpServerTestResult();
        bean.setSuccess(result.success());
        bean.setServerName(result.serverName());
        bean.setServerVersion(result.serverVersion());
        bean.setProtocolVersion(result.protocolVersion());
        bean.setTools(toToolBeans(result.tools()));
        bean.setError(result.error());
        bean.setLog(result.log());
        bean.setDurationMs(result.durationMs());
        return bean;
    }

    private static List<McpServerTool> toToolBeans(List<McpConnectionTester.ToolInfo> tools) {
        return tools.stream().map(t -> {
            McpServerTool tool = new McpServerTool();
            tool.setName(t.name());
            tool.setDescription(t.description());
            return tool;
        }).toList();
    }

    /**
     * Maps deletes of referenced servers to 409 with the references.
     *
     * @param e the exception
     * @return a 409 response
     */
    @ServerExceptionMapper
    public Response mapInUse(McpServerInUseException e) {
        McpServerInUseError body = new McpServerInUseError();
        body.setMessage(e.getMessage());
        body.setErrorCode(409);
        body.setUsages(e.usages().stream().map(McpServersResourceImpl::toUsageBean).toList());
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static McpServerUsage toUsageBean(McpServerReferences.Usage usage) {
        McpServerUsage bean = new McpServerUsage();
        bean.setType(McpServerUsage.Type.fromValue(usage.type().value()));
        bean.setId(usage.id());
        bean.setName(usage.name());
        bean.setField(McpServerUsage.Field.fromValue(usage.field()));
        bean.setTemplateId(usage.templateId());
        return bean;
    }

    /**
     * Maps validation failures to 400.
     *
     * @param e the exception
     * @return a 400 response
     */
    @ServerExceptionMapper
    public Response mapValidation(McpServerValidationException e) {
        return error(400, e.getMessage());
    }

    /**
     * Maps conflicts to 409.
     *
     * @param e the exception
     * @return a 409 response
     */
    @ServerExceptionMapper
    public Response mapConflict(McpServerConflictException e) {
        return error(409, e.getMessage());
    }

    /**
     * Maps missing servers to 404.
     *
     * @param e the exception
     * @return a 404 response
     */
    @ServerExceptionMapper
    public Response mapNotFound(McpServerNotFoundException e) {
        return error(404, e.getMessage());
    }

    private static Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", message, "errorCode", status)).build();
    }

    private McpServer toBean(McpServerEntity entity) {
        McpServer server = new McpServer();
        server.setId(entity.id);
        server.setName(entity.name);
        server.setDescription(entity.description);
        server.setServerCommand(entity.serverCommand);
        server.setServerUrl(entity.serverUrl);
        server.setTransport(McpServer.Transport.fromValue(entity.transport));
        server.setTimeout(entity.timeoutMs);
        server.setEnabled(entity.enabled);
        server.setWarnings(service.warnings(entity));
        server.setTools(toToolBeans(service.discoveredTools(entity)));
        server.setToolsDiscoveredOn(entity.toolsDiscoveredOn == null ? null : Date.from(entity.toolsDiscoveredOn));
        server.setCreatedOn(entity.createdOn == null ? null : Date.from(entity.createdOn));
        server.setModifiedOn(entity.modifiedOn == null ? null : Date.from(entity.modifiedOn));
        if (entity.headers != null) {
            try {
                server.setHeaders(objectMapper.readValue(entity.headers, Headers.class));
            } catch (Exception e) {
                LOG.warnf("Invalid headers JSON for MCP server '%s': %s", entity.name, e.getMessage());
            }
        }
        if (entity.serverArgs != null) {
            try {
                server.setServerArgs(objectMapper.readValue(entity.serverArgs, new TypeReference<List<String>>() { }));
            } catch (Exception e) {
                LOG.warnf("Invalid serverArgs JSON for MCP server '%s': %s", entity.name, e.getMessage());
            }
        }
        if (entity.serverEnv != null) {
            try {
                server.setServerEnv(objectMapper.readValue(entity.serverEnv, ServerEnv.class));
            } catch (Exception e) {
                LOG.warnf("Invalid serverEnv JSON for MCP server '%s': %s", entity.name, e.getMessage());
            }
        }
        return server;
    }
}
