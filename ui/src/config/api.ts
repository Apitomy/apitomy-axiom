/**
 * Returns the base URL for the Axiom API.
 *
 * In development, the Vite proxy handles routing /api requests to the backend.
 * In production, the API URL is injected via the AXIOM_API_URL environment variable
 * into a global config object on window.
 */
export function getApiBaseUrl(): string {
    const win = window as unknown as Record<string, unknown>;
    if (win.AXIOM_API_URL && typeof win.AXIOM_API_URL === "string") {
        return win.AXIOM_API_URL;
    }
    return "";
}

const API = `${getApiBaseUrl()}/api/v1`;

/**
 * Extracts a human-readable error message from a failed response.
 *
 * Prefers the `message` field of a JSON error body (e.g. the validation error
 * envelope returned with a 422), falling back to the provided default plus the
 * HTTP status code when no message is available.
 *
 * @param response the failed fetch response
 * @param fallback the default message prefix to use when no body message exists
 * @returns a message suitable for display to the user
 */
async function extractErrorMessage(response: Response, fallback: string): Promise<string> {
    try {
        const body = await response.clone().json();
        if (body && typeof body.message === "string" && body.message.trim()) {
            return body.message;
        }
    } catch {
        // Body was not JSON or could not be read; fall through to the default.
    }
    return `${fallback}: ${response.status}`;
}

// ── Types ─────────────────────────────────────────────────────────

export interface SystemHealth {
    status: string;
    version: string;
    timestamp: string;
}

export interface StartupCheck {
    name: string;
    status: "ok" | "warning" | "error";
    message: string;
}

export interface EngineInfo {
    type: string;
    label: string;
    available: boolean;
    supportsInteractiveSessions?: boolean;
    checks?: StartupCheck[];
    models?: string[];
}

export interface SystemConfig {
    version: string;
    engine?: string;
    defaultEngine?: string;
    engines?: EngineInfo[];
    features: Record<string, boolean>;
    checks?: StartupCheck[];
}

export interface SearchResults<T> {
    items: T[];
    totalCount: number;
    page: number;
    limit: number;
}

export interface Project {
    id: number;
    name: string;
    body?: string;
    type: string;
    status: string;
    refSource?: string;
    ref: string;
    repository?: string;
    createdOn: string;
    updatedOn: string;
    metadata?: Record<string, string>;
    labels?: string[];
    hasWorkflowInstance?: boolean;
}

export interface NewProject {
    name: string;
    body?: string;
    type: string;
    refSource?: string;
    ref: string;
    repository?: string;
}

export interface Task {
    id: number;
    projectId: number;
    eventId?: number;
    actionType: string;
    createdBy: string;
    assignedAgent?: number;
    status: string;
    input?: string;
    output?: string;
    createdOn: string;
    completedOn?: string;
    sessionId?: string;
    traceId?: string;
    workflowRunId?: number;
    nodeId?: string;
}

export interface ThreadEntry {
    id: number;
    projectId: number;
    authorType: string;
    authorId?: string;
    entryType: string;
    content: string;
    createdOn: string;
}

export interface ActivityLogEntry {
    id: number;
    projectId?: number;
    taskId?: number;
    eventId?: number;
    entryType: string;
    summary: string;
    details?: string;
    createdOn: string;
}

// ── System ────────────────────────────────────────────────────────

export async function fetchSystemHealth(): Promise<SystemHealth> {
    const response = await fetch(`${API}/system/health`);
    if (!response.ok) throw new Error(`Health check failed: ${response.status}`);
    return response.json();
}

export async function fetchSystemConfig(): Promise<SystemConfig> {
    const response = await fetch(`${API}/system/config`);
    if (!response.ok) throw new Error(`Failed to fetch config: ${response.status}`);
    return response.json();
}

// ── Configuration Packs ─────────────────────────────────────────

export interface PackExportRequest {
    name: string;
    description?: string;
    actionTypeIds?: number[];
    toolIds?: number[];
    toolsetIds?: number[];
    mcpServerIds?: number[];
    reportDefinitionIds?: number[];
    sessionTemplateIds?: string[];
    scheduledJobIds?: number[];
}

export interface ImportResult {
    actionTypes?: number;
    tools?: number;
    toolsets?: number;
    mcpServers?: number;
    reportDefinitions?: number;
    sessionTemplates?: number;
    scheduledJobs?: number;
}

export interface AssistantApplyResult {
    tools?: number;
    actionTypes?: number;
    reportDefinitions?: number;
    toolsets?: number;
    sessionTemplates?: number;
    scheduledJobs?: number;
    toolsCreated?: number;
    toolsUpdated?: number;
    actionTypesCreated?: number;
    actionTypesUpdated?: number;
    reportDefinitionsCreated?: number;
    reportDefinitionsUpdated?: number;
    toolsetsCreated?: number;
    toolsetsUpdated?: number;
    sessionTemplatesCreated?: number;
    sessionTemplatesUpdated?: number;
    eventSourcesCreated?: number;
    eventSourcesUpdated?: number;
    scheduledJobsCreated?: number;
    scheduledJobsUpdated?: number;
}

export async function exportPack(request: PackExportRequest): Promise<Blob> {
    const response = await fetch(`${API}/system/packs/export`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to export pack: ${response.status}`);
    return response.blob();
}

export async function importPack(packJson: string): Promise<ImportResult> {
    const response = await fetch(`${API}/system/packs/import`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: packJson,
    });
    if (!response.ok) {
        const body = await response.json();
        if (response.status === 409 && body.conflicts) {
            throw { status: 409, conflicts: body.conflicts };
        }
        throw new Error(`Failed to import pack: ${response.status}`);
    }
    return response.json();
}

export async function fetchModels(engine?: string): Promise<string[]> {
    const params = engine ? `?engine=${encodeURIComponent(engine)}` : "";
    const response = await fetch(`${API}/system/models${params}`);
    if (!response.ok) throw new Error(`Failed to fetch models: ${response.status}`);
    return response.json();
}

export async function fetchEngines(): Promise<string[]> {
    const response = await fetch(`${API}/system/engines`);
    if (!response.ok) throw new Error(`Failed to fetch engines: ${response.status}`);
    return response.json();
}

// ── Projects ──────────────────────────────────────────────────────

export async function fetchProjects(
    page = 1, limit = 20, filterName?: string, filterStatus?: string, filterLabels?: string
): Promise<SearchResults<Project>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/projects?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch projects: ${response.status}`);
    return response.json();
}

export async function fetchProject(id: number): Promise<Project> {
    const response = await fetch(`${API}/projects/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch project: ${response.status}`);
    return response.json();
}

export async function updateProject(id: number, data: Partial<Project>): Promise<Project> {
    const response = await fetch(`${API}/projects/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to update project: ${response.status}`);
    return response.json();
}

export async function createProject(project: NewProject): Promise<Project> {
    const response = await fetch(`${API}/projects`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(project),
    });
    if (!response.ok) throw new Error(`Failed to create project: ${response.status}`);
    return response.json();
}

export async function deleteProject(id: number): Promise<void> {
    const response = await fetch(`${API}/projects/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete project: ${response.status}`);
}

export async function updateProjectBody(id: number, body: string): Promise<void> {
    const response = await fetch(`${API}/projects/${id}/body`, {
        method: "PUT",
        headers: { "Content-Type": "text/markdown" },
        body,
    });
    if (!response.ok) throw new Error(`Failed to update project body: ${response.status}`);
}

// ── Tasks ─────────────────────────────────────────────────────────

export async function fetchAllTasks(
    page = 1, limit = 20,
    filterActionType?: string, filterStatus?: string, filterProjectId?: number
): Promise<SearchResults<Task>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterProjectId != null) params.set("filterProjectId", String(filterProjectId));
    const response = await fetch(`${API}/tasks?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch tasks: ${response.status}`);
    return response.json();
}

export async function cancelTask(projectId: number, taskId: number): Promise<Task> {
    const response = await fetch(`${API}/projects/${projectId}/tasks/${taskId}/cancel`, {
        method: "POST",
    });
    if (!response.ok) throw new Error(`Failed to cancel task: ${response.status}`);
    return response.json();
}

export async function fetchProjectTasks(projectId: number): Promise<Task[]> {
    const response = await fetch(`${API}/projects/${projectId}/tasks`);
    if (!response.ok) throw new Error(`Failed to fetch tasks: ${response.status}`);
    return response.json();
}

export interface NewTask {
    actionType: string;
    assignedAgent?: number;
    input?: string;
}

export async function createTask(projectId: number, task: NewTask): Promise<Task> {
    const response = await fetch(`${API}/projects/${projectId}/tasks`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(task),
    });
    if (!response.ok) throw new Error(`Failed to create task: ${response.status}`);
    return response.json();
}

export async function fetchTaskExecutionLog(
    projectId: number,
    taskId: number
): Promise<string> {
    const response = await fetch(
        `${API}/projects/${projectId}/tasks/${taskId}/log`
    );
    if (!response.ok) throw new Error(`Failed to fetch execution log: ${response.status}`);
    return response.text();
}

// ── Action Types ──────────────────────────────────────────────────

export interface ActionTypeField {
    name: string;
    type: "string" | "number" | "boolean" | "object";
    required?: boolean;
    description?: string;
}

export interface ActionType {
    id: number;
    name: string;
    description?: string;
    executionMode: string;
    userTriggerable: boolean;
    managerTriggerable: boolean;
    allowedTools?: string[];
    promptTemplate?: string;
    scriptTemplate?: string;
    model?: string;
    engine?: string;
    maxSteps?: number;
    maxBudgetUsd?: number;
    timeoutSeconds?: number;
    emitsEvent: boolean;
    environment?: Record<string, string>;
    labels?: string[];
    workflowEnabled?: boolean;
    inputs?: ActionTypeField[];
    outputs?: ActionTypeField[];
}

export type NewActionType = Omit<ActionType, "id">;

export async function fetchActionTypes(
    page = 1, limit = 20, filterName?: string, filterMode?: string,
    filterLabels?: string, filterWorkflowEnabled?: boolean
): Promise<SearchResults<ActionType>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterMode) params.set("filterMode", filterMode);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterWorkflowEnabled) params.set("filterWorkflowEnabled", "true");
    const response = await fetch(`${API}/action-types?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch action types: ${response.status}`);
    return response.json();
}

export async function fetchActionType(id: number): Promise<ActionType> {
    const response = await fetch(`${API}/action-types/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch action type: ${response.status}`);
    return response.json();
}

export async function validateActionType(at: NewActionType): Promise<ToolValidationResult> {
    const response = await fetch(`${API}/action-types/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(at),
    });
    if (!response.ok) throw new Error(`Failed to validate action type: ${response.status}`);
    return response.json();
}

export async function createActionType(at: NewActionType): Promise<ActionType> {
    const response = await fetch(`${API}/action-types`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(at),
    });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to create action type"));
    }
    return response.json();
}

export async function updateActionType(id: number, at: NewActionType): Promise<ActionType> {
    const response = await fetch(`${API}/action-types/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(at),
    });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to update action type"));
    }
    return response.json();
}

export interface ScriptAiEditRequest {
    message: string;
    currentScript?: string;
    actionTypeName?: string;
    actionTypeDescription?: string;
}

export interface ScriptAiEditResponse {
    script?: string;
    explanation?: string;
}

export async function aiEditScript(request: ScriptAiEditRequest): Promise<ScriptAiEditResponse> {
    const response = await fetch(`${API}/action-types/ai-edit-script`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to AI edit script: ${response.status}`);
    return response.json();
}

export async function aiEditActionPrompt(request: ReportAiEditRequest): Promise<ReportAiEditResponse> {
    const response = await fetch(`${API}/action-types/ai-edit-prompt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to AI edit action prompt: ${response.status}`);
    return response.json();
}

export async function deleteActionType(id: number): Promise<void> {
    const response = await fetch(`${API}/action-types/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete action type: ${response.status}`);
}

// ── Agents ────────────────────────────────────────────────────────

export interface Agent {
    id: number;
    name: string;
    description?: string;
    agentType: string;
    enabled?: boolean;
    capabilities?: string[];
}

export type NewAgent = Omit<Agent, "id">;

export async function fetchAgents(): Promise<Agent[]> {
    const response = await fetch(`${API}/agents`);
    if (!response.ok) throw new Error(`Failed to fetch agents: ${response.status}`);
    return response.json();
}

export async function createAgent(agent: NewAgent): Promise<Agent> {
    const response = await fetch(`${API}/agents`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(agent),
    });
    if (!response.ok) throw new Error(`Failed to create agent: ${response.status}`);
    return response.json();
}

export async function updateAgent(id: number, agent: NewAgent): Promise<Agent> {
    const response = await fetch(`${API}/agents/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(agent),
    });
    if (!response.ok) throw new Error(`Failed to update agent: ${response.status}`);
    return response.json();
}

export async function deleteAgent(id: number): Promise<void> {
    const response = await fetch(`${API}/agents/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete agent: ${response.status}`);
}

export async function fetchAgent(id: number): Promise<Agent> {
    const response = await fetch(`${API}/agents/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch agent: ${response.status}`);
    return response.json();
}

export async function fetchAgentTasks(
    agentId: number, page = 1, limit = 20,
    filterActionType?: string, filterStatus?: string
): Promise<SearchResults<Task>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    const response = await fetch(`${API}/agents/${agentId}/tasks?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch agent tasks: ${response.status}`);
    return response.json();
}

// ── Tool Definitions ──────────────────────────────────────────────

export interface ToolParameter {
    name: string;
    type: string;
    description?: string;
    required?: boolean;
}

export interface ToolDefinition {
    id: number;
    name: string;
    description?: string;
    parameters?: ToolParameter[];
    scriptTemplate?: string;
    labels?: string[];
}

export interface McpServer {
    id: number;
    name: string;
    description?: string;
    serverCommand?: string;
    serverArgs?: string[];
    serverEnv?: Record<string, string>;
    serverUrl?: string;
}

export type NewMcpServer = Omit<McpServer, "id">;

export type NewToolDefinition = Omit<ToolDefinition, "id">;

export async function fetchTools(
    page = 1, limit = 20, filterName?: string, filterLabels?: string
): Promise<SearchResults<ToolDefinition>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/tools?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch tools: ${response.status}`);
    return response.json();
}

export async function fetchTool(id: number): Promise<ToolDefinition> {
    const response = await fetch(`${API}/tools/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch tool: ${response.status}`);
    return response.json();
}

export async function createTool(tool: NewToolDefinition): Promise<ToolDefinition> {
    const response = await fetch(`${API}/tools`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tool),
    });
    if (!response.ok) throw new Error(`Failed to create tool: ${response.status}`);
    return response.json();
}

export async function updateTool(id: number, tool: NewToolDefinition): Promise<ToolDefinition> {
    const response = await fetch(`${API}/tools/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tool),
    });
    if (!response.ok) throw new Error(`Failed to update tool: ${response.status}`);
    return response.json();
}

export async function deleteTool(id: number): Promise<void> {
    const response = await fetch(`${API}/tools/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete tool: ${response.status}`);
}

export interface ToolTestRequest {
    scriptTemplate?: string;
    parameters?: Record<string, string>;
}

export interface ToolTestResponse {
    success: boolean;
    exitCode: number;
    output: string;
    resolvedScript: string;
    durationMs: number;
}

export async function testTool(id: number, request: ToolTestRequest): Promise<ToolTestResponse> {
    const response = await fetch(`${API}/tools/${id}/test`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to test tool: ${response.status}`);
    return response.json();
}

export interface ToolValidationMessage {
    severity: "error" | "warning";
    field: string;
    message: string;
}

export interface ToolValidationResult {
    valid: boolean;
    messages: ToolValidationMessage[];
}

export async function validateTool(tool: NewToolDefinition): Promise<ToolValidationResult> {
    const response = await fetch(`${API}/tools/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tool),
    });
    if (!response.ok) throw new Error(`Failed to validate tool: ${response.status}`);
    return response.json();
}

export interface ToolAiEditRequest {
    message: string;
    currentTool?: NewToolDefinition;
}

export interface ToolAiEditResponse {
    tool?: NewToolDefinition;
    explanation?: string;
}

export async function aiEditTool(request: ToolAiEditRequest): Promise<ToolAiEditResponse> {
    const response = await fetch(`${API}/tools/ai-edit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to AI edit tool: ${response.status}`);
    return response.json();
}

export async function fetchMcpServers(): Promise<McpServer[]> {
    const response = await fetch(`${API}/mcp-servers`);
    if (!response.ok) throw new Error(`Failed to fetch MCP servers: ${response.status}`);
    return response.json();
}

export async function fetchMcpServer(id: number): Promise<McpServer> {
    const response = await fetch(`${API}/mcp-servers/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch MCP server: ${response.status}`);
    return response.json();
}

export async function createMcpServer(server: NewMcpServer): Promise<McpServer> {
    const response = await fetch(`${API}/mcp-servers`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(server),
    });
    if (!response.ok) throw new Error(`Failed to create MCP server: ${response.status}`);
    return response.json();
}

export async function updateMcpServer(id: number, server: NewMcpServer): Promise<McpServer> {
    const response = await fetch(`${API}/mcp-servers/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(server),
    });
    if (!response.ok) throw new Error(`Failed to update MCP server: ${response.status}`);
    return response.json();
}

export async function deleteMcpServer(id: number): Promise<void> {
    const response = await fetch(`${API}/mcp-servers/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete MCP server: ${response.status}`);
}

export async function fetchActionTypeTools(actionTypeId: number): Promise<ToolDefinition[]> {
    const response = await fetch(`${API}/action-types/${actionTypeId}/tools`);
    if (!response.ok) throw new Error(`Failed to fetch action type tools: ${response.status}`);
    return response.json();
}

// ── Toolsets ─────────────────────────────────────────────────────

export interface Toolset {
    id: number;
    name: string;
    description?: string;
    tools: string[];
}

export type NewToolset = Omit<Toolset, "id">;

export async function fetchToolsets(): Promise<Toolset[]> {
    const response = await fetch(`${API}/toolsets`);
    if (!response.ok) throw new Error(`Failed to fetch toolsets: ${response.status}`);
    return response.json();
}

export async function fetchToolset(id: number): Promise<Toolset> {
    const response = await fetch(`${API}/toolsets/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch toolset: ${response.status}`);
    return response.json();
}

export async function createToolset(toolset: NewToolset): Promise<Toolset> {
    const response = await fetch(`${API}/toolsets`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(toolset),
    });
    if (!response.ok) throw new Error(`Failed to create toolset: ${response.status}`);
    return response.json();
}

export async function updateToolset(id: number, toolset: NewToolset): Promise<Toolset> {
    const response = await fetch(`${API}/toolsets/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(toolset),
    });
    if (!response.ok) throw new Error(`Failed to update toolset: ${response.status}`);
    return response.json();
}

export async function deleteToolset(id: number): Promise<void> {
    const response = await fetch(`${API}/toolsets/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete toolset: ${response.status}`);
}

// ── Secrets ──────────────────────────────────────────────────────

export interface Secret {
    id: number;
    name: string;
    description?: string;
}

export interface NewSecret {
    name: string;
    description?: string;
    value: string;
}

export async function fetchSecrets(): Promise<Secret[]> {
    const response = await fetch(`${API}/secrets`);
    if (!response.ok) throw new Error(`Failed to fetch secrets: ${response.status}`);
    return response.json();
}

export async function createSecret(secret: NewSecret): Promise<Secret> {
    const response = await fetch(`${API}/secrets`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(secret),
    });
    if (!response.ok) throw new Error(`Failed to create secret: ${response.status}`);
    return response.json();
}

export async function updateSecret(id: number, secret: NewSecret): Promise<Secret> {
    const response = await fetch(`${API}/secrets/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(secret),
    });
    if (!response.ok) throw new Error(`Failed to update secret: ${response.status}`);
    return response.json();
}

export async function deleteSecret(id: number): Promise<void> {
    const response = await fetch(`${API}/secrets/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete secret: ${response.status}`);
}

// ── Manager Configuration ────────────────────────────────────────

export interface ManagerConfig {
    systemPrompt?: string;
    promptTemplate?: string;
}

export async function fetchManagerConfig(): Promise<ManagerConfig> {
    const response = await fetch(`${API}/manager/config`);
    if (!response.ok) throw new Error(`Failed to fetch manager config: ${response.status}`);
    return response.json();
}

export async function updateManagerConfig(config: ManagerConfig): Promise<ManagerConfig> {
    const response = await fetch(`${API}/manager/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config),
    });
    if (!response.ok) throw new Error(`Failed to update manager config: ${response.status}`);
    return response.json();
}

// ── Retention Configuration ─────────────────────────────────────

export interface RetentionConfig {
    closedProjectRetentionDays?: number;
    traceRetentionDays?: number;
    eventRetentionDays?: number;
    eventSourceLogRetentionDays?: number;
}

export async function fetchRetentionConfig(): Promise<RetentionConfig> {
    const response = await fetch(`${API}/system/retention`);
    if (!response.ok) throw new Error(`Failed to fetch retention config: ${response.status}`);
    return response.json();
}

export async function updateRetentionConfig(config: RetentionConfig): Promise<RetentionConfig> {
    const response = await fetch(`${API}/system/retention`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config),
    });
    if (!response.ok) throw new Error(`Failed to update retention config: ${response.status}`);
    return response.json();
}

// ── Event Sources ────────────────────────────────────────────────

export interface EventSource {
    id: number;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    pollInterval?: number;
    secretName?: string;
    configuration?: Record<string, string>;
    labels?: string[];
    filters?: EventSourceFilters;
}

export type NewEventSource = Omit<EventSource, "id">;

export async function fetchEventSources(
    page = 1, limit = 20, filterName?: string, filterType?: string, filterLabels?: string
): Promise<SearchResults<EventSource>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterType) params.set("filterType", filterType);
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/event-sources?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch event sources: ${response.status}`);
    return response.json();
}

export async function createEventSource(source: NewEventSource): Promise<EventSource> {
    const response = await fetch(`${API}/event-sources`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(source),
    });
    if (!response.ok) throw new Error(`Failed to create event source: ${response.status}`);
    return response.json();
}

export async function updateEventSource(id: number, source: NewEventSource): Promise<EventSource> {
    const response = await fetch(`${API}/event-sources/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(source),
    });
    if (!response.ok) throw new Error(`Failed to update event source: ${response.status}`);
    return response.json();
}

export async function deleteEventSource(id: number): Promise<void> {
    const response = await fetch(`${API}/event-sources/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete event source: ${response.status}`);
}

export async function fetchEventSource(id: number): Promise<EventSource> {
    const response = await fetch(`${API}/event-sources/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch event source: ${response.status}`);
    return response.json();
}

export async function dryRunFilters(request: FilterDryRunRequest): Promise<FilterDryRunResponse> {
    const response = await fetch(`${API}/event-sources/filters/dry-run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Dry run failed: ${response.statusText}`);
    return response.json();
}

export interface EventSourceLog {
    id: number;
    eventSourceId: number;
    status: string;
    message: string;
    detail?: string;
    eventsIngested?: number;
    createdOn: string;
}

export interface EventSourceFilterRule {
    type: "event-type" | "payload";
    pointer?: string;
    pattern: string;
}

export interface EventSourceFilters {
    include: EventSourceFilterRule[];
    exclude: EventSourceFilterRule[];
}

export interface FilterDryRunRequest {
    sourceType: string;
    configuration: Record<string, string>;
    secretName?: string;
    filters: EventSourceFilters;
}

export interface FilterDryRunResult {
    eventType: string;
    issueRef: string;
    summary: string;
    allowed: boolean;
    matchedRule?: string;
    payload?: string;
}

export interface FilterDryRunResponse {
    results: FilterDryRunResult[];
    totalEvaluated: number;
    totalAllowed: number;
    totalBlocked: number;
}

export async function fetchEventSourceLogs(
    id: number, page = 1, limit = 20
): Promise<SearchResults<EventSourceLog>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    const response = await fetch(`${API}/event-sources/${id}/logs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch event source logs: ${response.status}`);
    return response.json();
}

// ── Thread ────────────────────────────────────────────────────────

export async function fetchThreadEntries(projectId: number): Promise<ThreadEntry[]> {
    const response = await fetch(`${API}/projects/${projectId}/thread`);
    if (!response.ok) throw new Error(`Failed to fetch thread: ${response.status}`);
    return response.json();
}

// ── Reports ──────────────────────────────────────────────────────

export interface ReportDefinition {
    id: number;
    name: string;
    description?: string;
    slug?: string;
    schedule: string;
    scheduleTime?: string;
    scheduleDayOfWeek?: string;
    timeWindow?: string;
    promptTemplate: string;
    titleTemplate?: string;
    allowedTools?: string[];
    enabled: boolean;
    engine?: string;
    model?: string;
    maxSteps?: number;
    maxBudgetUsd?: number;
    timeoutSeconds?: number;
    environment?: Record<string, string>;
    initialLabels?: string[];
    nextRunAt?: string;
    lastRunAt?: string;
    createdOn: string;
    updatedOn: string;
}

export type NewReportDefinition = Omit<ReportDefinition, "id" | "createdOn" | "updatedOn" | "nextRunAt" | "lastRunAt">;

export interface Report {
    id: number;
    definitionId: number;
    status: string;
    title?: string;
    content?: string;
    timeRangeStart?: string;
    timeRangeEnd?: string;
    costUsd?: number;
    durationMs?: number;
    labels?: string[];
    createdOn: string;
    completedOn?: string;
    traceId?: string;
}

export interface ReportAiEditRequest {
    message: string;
    currentPromptTemplate?: string;
    currentAllowedTools?: string[];
    reportName?: string;
    reportDescription?: string;
}

export interface ReportAiEditResponse {
    promptTemplate?: string;
    allowedTools?: string[];
    explanation?: string;
}

export async function aiEditReportPrompt(request: ReportAiEditRequest): Promise<ReportAiEditResponse> {
    const response = await fetch(`${API}/reports/ai-edit-prompt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Failed to AI edit report prompt: ${response.status}`);
    return response.json();
}

export async function fetchReportDefinitions(): Promise<ReportDefinition[]> {
    const response = await fetch(`${API}/reports/definitions`);
    if (!response.ok) throw new Error(`Failed to fetch report definitions: ${response.status}`);
    return response.json();
}

export async function fetchReportDefinition(id: number): Promise<ReportDefinition> {
    const response = await fetch(`${API}/reports/definitions/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch report definition: ${response.status}`);
    return response.json();
}

export async function validateReportDefinition(
    def: NewReportDefinition
): Promise<ToolValidationResult> {
    const response = await fetch(`${API}/reports/definitions/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(def),
    });
    if (!response.ok) throw new Error(`Failed to validate report definition: ${response.status}`);
    return response.json();
}

export async function createReportDefinition(def: NewReportDefinition): Promise<ReportDefinition> {
    const response = await fetch(`${API}/reports/definitions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(def),
    });
    if (!response.ok) throw new Error(`Failed to create report definition: ${response.status}`);
    return response.json();
}

export async function updateReportDefinition(id: number, def: NewReportDefinition): Promise<ReportDefinition> {
    const response = await fetch(`${API}/reports/definitions/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(def),
    });
    if (!response.ok) throw new Error(`Failed to update report definition: ${response.status}`);
    return response.json();
}

export async function deleteReportDefinition(id: number): Promise<void> {
    const response = await fetch(`${API}/reports/definitions/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete report definition: ${response.status}`);
}

export async function runReportDefinition(id: number): Promise<Report> {
    const response = await fetch(`${API}/reports/definitions/${id}/run`, { method: "POST" });
    if (!response.ok) throw new Error(`Failed to run report: ${response.status}`);
    return response.json();
}

export async function fetchReports(
    page = 1, limit = 20,
    filterDefinitionId?: number, filterStatus?: string, filterTitle?: string,
    filterLabels?: string
): Promise<SearchResults<Report>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterDefinitionId != null) params.set("filterDefinitionId", String(filterDefinitionId));
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterTitle) params.set("filterTitle", filterTitle);
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/reports?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch reports: ${response.status}`);
    return response.json();
}

export async function fetchReportExecutionLog(reportId: number): Promise<string> {
    const response = await fetch(`${API}/reports/${reportId}/log`);
    if (!response.ok) throw new Error(`Failed to fetch report log: ${response.status}`);
    return response.text();
}

export async function fetchReport(id: number): Promise<Report> {
    const response = await fetch(`${API}/reports/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch report: ${response.status}`);
    return response.json();
}

export async function updateReportLabels(reportId: number, labels: string[]): Promise<Report> {
    const response = await fetch(`${API}/reports/${reportId}/labels`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(labels),
    });
    if (!response.ok) throw new Error(`Failed to update report labels: ${response.status}`);
    return response.json();
}

export async function deleteReport(id: number): Promise<void> {
    const response = await fetch(`${API}/reports/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete report: ${response.status}`);
}

// ── Metrics ──────────────────────────────────────────────────────

export interface ProjectMetrics {
    projectId: number;
    diskUsageBytes: number;
    totalCostUsd: number;
    totalInputTokens: number;
    totalOutputTokens: number;
    invocationCount: number;
}

export async function fetchProjectMetrics(projectId: number): Promise<ProjectMetrics> {
    const response = await fetch(`${API}/projects/${projectId}/metrics`);
    if (!response.ok) throw new Error(`Failed to fetch project metrics: ${response.status}`);
    return response.json();
}

export function formatBytes(bytes: number): string {
    if (bytes === 0) return "0 B";
    const units = ["B", "KB", "MB", "GB"];
    const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const value = bytes / Math.pow(1024, i);
    return `${value.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

// ── AI Usage ─────────────────────────────────────────────────────

export interface AiUsage {
    id: number;
    invocationType: string;
    taskId?: number;
    eventId?: number;
    projectId?: number;
    agentId?: number;
    actionType?: string;
    engine?: string;
    model?: string;
    costUsd?: number;
    inputTokens?: number;
    outputTokens?: number;
    durationMs?: number;
    createdOn: string;
}

export interface AiUsageSearchResults extends SearchResults<AiUsage> {
    totalCostUsd: number;
    totalInputTokens: number;
    totalOutputTokens: number;
}

export async function fetchUsage(
    page = 1, limit = 20,
    filterInvocationType?: string, filterProjectId?: number,
    filterAgentId?: number, filterActionType?: string,
    filterDateFrom?: string, filterDateTo?: string,
    filterLabels?: string, filterEngine?: string,
    filterModel?: string
): Promise<AiUsageSearchResults> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterInvocationType) params.set("filterInvocationType", filterInvocationType);
    if (filterProjectId != null) params.set("filterProjectId", String(filterProjectId));
    if (filterAgentId != null) params.set("filterAgentId", String(filterAgentId));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterDateFrom) params.set("filterDateFrom", filterDateFrom);
    if (filterDateTo) params.set("filterDateTo", filterDateTo);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterEngine) params.set("filterEngine", filterEngine);
    if (filterModel) params.set("filterModel", filterModel);
    const response = await fetch(`${API}/usage/ai?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch usage: ${response.status}`);
    return response.json();
}

// ── Disk Usage ──────────────────────────────────────────────────

export interface DiskUsageProject {
    projectId: number;
    projectName: string;
    diskUsageBytes: number;
}

export interface DiskUsageSearchResults extends SearchResults<DiskUsageProject> {
    totalDiskUsageBytes: number;
    projectCount: number;
}

export async function fetchDiskUsage(
    page = 1, limit = 20, filterName?: string,
    sortBy?: "name" | "diskUsageBytes", sortOrder?: "asc" | "desc"
): Promise<DiskUsageSearchResults> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (sortBy) params.set("sortBy", sortBy);
    if (sortOrder) params.set("sortOrder", sortOrder);
    const response = await fetch(`${API}/usage/disk?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch disk usage: ${response.status}`);
    return response.json();
}

// ── Events ───────────────────────────────────────────────────────

export interface AxiomEvent {
    id: number;
    eventSourceId?: number;
    source: string;
    eventType: string;
    issueRef?: string;
    repository?: string;
    projectId?: number;
    taskId?: number;
    payload?: string;
    receivedAt: string;
    traceId?: string;
    labels?: string[];
    filterStatus?: string;
    filterMatchedRule?: string;
}

export async function fetchEvent(id: number): Promise<AxiomEvent> {
    const response = await fetch(`${API}/events/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch event: ${response.status}`);
    return response.json();
}

export async function fetchProjectEvents(projectId: number): Promise<AxiomEvent[]> {
    const response = await fetch(`${API}/projects/${projectId}/events`);
    if (!response.ok) throw new Error(`Failed to fetch project events: ${response.status}`);
    return response.json();
}

export async function fetchEvents(
    page = 1, limit = 20,
    filterSource?: string, filterEventType?: string, filterRepository?: string,
    filterLabels?: string, filterFilterStatus?: string, filterEventSourceId?: number
): Promise<SearchResults<AxiomEvent>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterSource) params.set("filterSource", filterSource);
    if (filterEventType) params.set("filterEventType", filterEventType);
    if (filterRepository) params.set("filterRepository", filterRepository);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterFilterStatus) params.set("filterFilterStatus", filterFilterStatus);
    if (filterEventSourceId != null) params.set("filterEventSourceId", String(filterEventSourceId));
    const response = await fetch(`${API}/events?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch events: ${response.status}`);
    return response.json();
}

// ── Activity Log ──────────────────────────────────────────────────

export async function fetchActivityLog(
    page = 1, limit = 20,
    filterEventId?: number, filterSummary?: string,
    filterProjectId?: number, filterEntryType?: string,
    filterLabels?: string
): Promise<SearchResults<ActivityLogEntry>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterEventId != null) params.set("filterEventId", String(filterEventId));
    if (filterSummary) params.set("filterSummary", filterSummary);
    if (filterProjectId != null) params.set("filterProjectId", String(filterProjectId));
    if (filterEntryType) params.set("filterEntryType", filterEntryType);
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/activity?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch activity log: ${response.status}`);
    return response.json();
}

export async function fetchActivityLogDetails(activityId: number): Promise<string> {
    const response = await fetch(`${API}/activity/${activityId}/log`);
    if (!response.ok) throw new Error(`Failed to fetch activity log details: ${response.status}`);
    return response.text();
}

// ── AI Assistant ────────────────────────────────────────────────

export interface SessionTemplate {
    templateId: string;
    name: string;
    description: string;
    builtIn: boolean;
    systemPrompt: string;
    welcomeMessage?: string;
    initialMessage?: string;
    workingDirectory?: string;
    model?: string;
    initScript?: string;
    initScriptType?: string;
    environment?: Record<string, string>;
    mcpServers: string[];
    allowedTools: string[];
}

export interface NewSessionTemplate {
    templateId?: string;
    name: string;
    description: string;
    systemPrompt: string;
    welcomeMessage?: string;
    initialMessage?: string;
    workingDirectory?: string;
    model?: string;
    initScript?: string;
    initScriptType?: string;
    environment?: Record<string, string>;
    mcpServers?: string[];
    allowedTools?: string[];
}

export interface AssistantSessionInfo {
    id: string;
    name: string;
    templateId: string;
    status: "starting" | "running" | "stopped" | "error";
    createdAt: string;
    lastActivityAt: string;
    errorMessage?: string;
    totalCostUsd?: number;
    totalInputTokens?: number;
    totalOutputTokens?: number;
    turnCount?: number;
    projectId?: number;
    projectName?: string;
    allowAll?: boolean;
}

export interface AssistantItem {
    type: string;
    name: string;
    valid: boolean;
    validationErrors?: string[];
}

export async function createAssistantSession(
    templateId: string,
    name?: string,
    projectId?: number
): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, templateId, projectId }),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        const message = body?.message || `Failed to create session (${response.status})`;
        throw new Error(message);
    }
    return response.json();
}

export async function fetchAssistantSessions(): Promise<AssistantSessionInfo[]> {
    const response = await fetch(`${API}/assistant/sessions`);
    if (!response.ok) throw new Error(`Failed to fetch sessions: ${response.status}`);
    return response.json();
}

export async function fetchAssistantSession(id: string): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch session: ${response.status}`);
    return response.json();
}

export interface AssistantHistoryEvent {
    eventType: string;
    eventData: Record<string, unknown>;
    eventIndex: number;
}

export async function fetchAssistantSessionHistory(
    sessionId: string
): Promise<AssistantHistoryEvent[]> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/history`);
    if (!response.ok) throw new Error(`Failed to fetch session history: ${response.status}`);
    return response.json();
}

export async function deleteAssistantSession(id: string): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete session: ${response.status}`);
}

export async function sendAssistantMessage(sessionId: string, message: string): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message }),
    });
    if (!response.ok) throw new Error(`Failed to send message: ${response.status}`);
}

export async function respondToAssistantPermission(
    sessionId: string, permissionId: string, allow: boolean,
    updatedInput?: Record<string, unknown>
): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/permissions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ permissionId, allow, updatedInput }),
    });
    if (!response.ok) throw new Error(`Failed to respond to permission: ${response.status}`);
}

export async function dismissAssistantCard(sessionId: string, cardId: string): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/dismiss-card`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cardId }),
    });
    if (!response.ok) throw new Error(`Failed to dismiss card: ${response.status}`);
}

export async function fetchAssistantItems(sessionId: string): Promise<AssistantItem[]> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/items`);
    if (!response.ok) throw new Error(`Failed to fetch items: ${response.status}`);
    return response.json();
}

export async function fetchAssistantItemContent(
    sessionId: string, itemType: string, itemName: string
): Promise<Record<string, unknown>> {
    const response = await fetch(
        `${API}/assistant/sessions/${sessionId}/items/${itemType}/${encodeURIComponent(itemName)}`
    );
    if (!response.ok) throw new Error(`Failed to fetch item: ${response.status}`);
    return response.json();
}

export async function applyAssistantSession(sessionId: string): Promise<AssistantApplyResult> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/apply`, {
        method: "POST",
    });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw { status: response.status, ...body };
    }
    return response.json();
}

export async function renameAssistantSession(
    sessionId: string, name: string
): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
    });
    if (!response.ok) throw new Error("Failed to rename session");
    return response.json();
}

export async function interruptAssistantSession(sessionId: string): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/interrupt`, {
        method: "POST",
    });
    if (!response.ok) throw new Error("Failed to interrupt session");
}

export interface AutoApprovalRule {
    id: string;
    toolName: string;
    fieldName?: string;
    pattern?: string;
    createdAt: string;
}

export interface CreateAutoApprovalRequest {
    toolName: string;
    fieldName?: string;
    pattern?: string;
    permissionId?: string;
}

export async function fetchAutoApprovals(sessionId: string): Promise<AutoApprovalRule[]> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/auto-approvals`);
    if (!response.ok) throw new Error("Failed to fetch auto-approvals");
    return response.json();
}

export async function createAutoApproval(
    sessionId: string, data: CreateAutoApprovalRequest
): Promise<AutoApprovalRule> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/auto-approvals`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || "Failed to create auto-approval");
    }
    return response.json();
}

export async function deleteAutoApproval(sessionId: string, ruleId: string): Promise<void> {
    const response = await fetch(
        `${API}/assistant/sessions/${sessionId}/auto-approvals/${encodeURIComponent(ruleId)}`,
        { method: "DELETE" }
    );
    if (!response.ok) throw new Error("Failed to delete auto-approval");
}

export async function setAllowAll(sessionId: string, enabled: boolean): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/allow-all`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled }),
    });
    if (!response.ok) throw new Error("Failed to update Allow All mode");
}

export async function setSubagentAllowAll(
    sessionId: string, subagentToolUseId: string, enabled: boolean
): Promise<void> {
    const response = await fetch(
        `${API}/assistant/sessions/${sessionId}/subagent-allow-all`,
        {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ subagentToolUseId, enabled }),
        }
    );
    if (!response.ok) throw new Error("Failed to update subagent Allow All mode");
}

export function assistantEventsUrl(sessionId: string): string {
    return `${API}/assistant/sessions/${sessionId}/events`;
}

export async function fetchAssistantTemplates(): Promise<SessionTemplate[]> {
    const response = await fetch(`${API}/assistant/templates`);
    if (!response.ok) throw new Error("Failed to fetch templates");
    return response.json();
}

export async function fetchAssistantTemplate(templateId: string): Promise<SessionTemplate> {
    const response = await fetch(`${API}/assistant/templates/${encodeURIComponent(templateId)}`);
    if (!response.ok) throw new Error("Failed to fetch template");
    return response.json();
}

export async function createAssistantTemplate(
    data: NewSessionTemplate
): Promise<SessionTemplate> {
    const response = await fetch(`${API}/assistant/templates`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error("Failed to create template");
    return response.json();
}

export async function updateAssistantTemplate(
    templateId: string,
    data: NewSessionTemplate
): Promise<SessionTemplate> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}`,
        {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        }
    );
    if (!response.ok) {
        if (response.status === 403) throw new Error("Cannot modify built-in template");
        throw new Error("Failed to update template");
    }
    return response.json();
}

export async function deleteAssistantTemplate(templateId: string): Promise<void> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}`,
        { method: "DELETE" }
    );
    if (!response.ok) {
        if (response.status === 403) throw new Error("Cannot delete built-in template");
        throw new Error("Failed to delete template");
    }
}

// ── Traces ──────────────────────────────────────────────────────────

export interface Trace {
    traceId: string;
    traceType: string;
    status: string;
    summary: string;
    eventId?: number;
    projectId?: number;
    reportId?: number;
    startedOn: string;
    completedOn?: string;
}

export interface TraceNode {
    id: number;
    traceId: string;
    parentNodeId?: number;
    nodeType: string;
    status: string;
    summary: string;
    startedOn: string;
    completedOn?: string;
    durationMs?: number;
    entityType?: string;
    entityId?: number;
}

export interface ToolExecution {
    id: number;
    traceId: string;
    toolName: string;
    toolInput?: string;
    toolOutput?: string;
    status: string;
    durationMs?: number;
    createdOn: string;
}

export interface TraceDetail {
    trace: Trace;
    nodes: TraceNode[];
}

export interface TraceNodeDetailResponse {
    node: TraceNode;
    detail: Record<string, unknown>;
}

export async function fetchTraces(
    page = 1, limit = 20,
    filterTraceType?: string, filterStatus?: string,
    filterEventId?: number, filterProjectId?: number,
    filterReportId?: number
): Promise<SearchResults<Trace>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterTraceType) params.set("filterTraceType", filterTraceType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterEventId) params.set("filterEventId", String(filterEventId));
    if (filterProjectId) params.set("filterProjectId", String(filterProjectId));
    if (filterReportId) params.set("filterReportId", String(filterReportId));
    const response = await fetch(`${API}/traces?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch traces: ${response.status}`);
    return response.json();
}

export async function fetchTraceDetail(traceId: string): Promise<TraceDetail> {
    const response = await fetch(`${API}/traces/${traceId}`);
    if (!response.ok) throw new Error(`Failed to fetch trace: ${response.status}`);
    return response.json();
}

export async function fetchTraceNodeDetail(
    traceId: string, nodeId: number
): Promise<TraceNodeDetailResponse> {
    const response = await fetch(`${API}/traces/${traceId}/nodes/${nodeId}`);
    if (!response.ok) throw new Error(`Failed to fetch node detail: ${response.status}`);
    return response.json();
}

export async function fetchEventTraces(eventId: number): Promise<Trace[]> {
    const response = await fetch(`${API}/events/${eventId}/traces`);
    if (!response.ok) throw new Error(`Failed to fetch event traces: ${response.status}`);
    return response.json();
}

export async function fetchProjectTraces(projectId: number): Promise<Trace[]> {
    const response = await fetch(`${API}/projects/${projectId}/traces`);
    if (!response.ok) throw new Error(`Failed to fetch project traces: ${response.status}`);
    return response.json();
}

export async function fetchReportTraces(reportId: number): Promise<Trace[]> {
    const response = await fetch(`${API}/reports/${reportId}/traces`);
    if (!response.ok) throw new Error(`Failed to fetch report traces: ${response.status}`);
    return response.json();
}

// ── Inbox ────────────────────────────────────────────────────────

export interface HumanContextReference {
    label: string;
    url: string;
}

export interface HumanContext {
    title: string;
    description?: string;
    references?: HumanContextReference[];
    details?: { label: string; value: string }[];
}

export interface OutputSchemaFieldOption {
    label: string;
    value: string;
}

export interface OutputSchemaField {
    name: string;
    type: "text" | "textarea" | "boolean" | "select" | "number";
    label: string;
    description?: string;
    required: boolean;
    defaultValue?: unknown;
    options?: OutputSchemaFieldOption[];
}

export interface OutputSchema {
    fields: OutputSchemaField[];
}

export interface InboxItem {
    id: number;
    projectId: number;
    projectName?: string;
    actionType: string;
    status: string;
    input?: string;
    humanContext?: HumanContext;
    outputSchema?: OutputSchema;
    createdOn: string;
    eventId?: number;
    traceId?: string;
}

export interface InboxCount {
    count: number;
}

export async function fetchInboxItems(
    page = 1, limit = 20, filterLabels?: string
): Promise<SearchResults<InboxItem>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/inbox?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch inbox items: ${response.status}`);
    return response.json();
}

export interface NewInboxItem {
    projectId: number;
    actionType: string;
    humanContext: HumanContext;
    outputSchema?: OutputSchema;
}

export async function createInboxItem(data: NewInboxItem): Promise<InboxItem> {
    const response = await fetch(`${API}/inbox`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to create inbox item: ${response.status}`);
    return response.json();
}

export async function fetchInboxCount(): Promise<InboxCount> {
    const response = await fetch(`${API}/inbox/count`);
    if (!response.ok) throw new Error(`Failed to fetch inbox count: ${response.status}`);
    return response.json();
}

export async function fetchInboxItem(taskId: number): Promise<InboxItem> {
    const response = await fetch(`${API}/inbox/${taskId}`);
    if (!response.ok) throw new Error(`Failed to fetch inbox item: ${response.status}`);
    return response.json();
}

export async function completeInboxItem(taskId: number, data: Record<string, unknown>): Promise<void> {
    const response = await fetch(`${API}/inbox/${taskId}/complete`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        const error = await response.text();
        throw new Error(`Failed to complete inbox item: ${error}`);
    }
}

// ── Dashboard Types ──────────────────────────────────────────────

export interface DashboardWidgetLayout {
    x: number;
    y: number;
    w: number;
    h: number;
}

export interface DashboardWidget {
    id: string;
    type: string;
    config: Record<string, unknown>;
    layout: DashboardWidgetLayout;
}

export interface DashboardTab {
    id: string;
    name: string;
    widgets: DashboardWidget[];
}

export interface Dashboard {
    id: number;
    name: string;
    description?: string;
    labels: string[];
    isDefault: boolean;
    tabs: DashboardTab[];
    createdOn: string;
    updatedOn: string;
}

export type NewDashboard = Omit<Dashboard, "id" | "createdOn" | "updatedOn">;

// ── Dashboard API ────────────────────────────────────────────────

export async function fetchDashboards(): Promise<Dashboard[]> {
    const response = await fetch(`${API}/dashboards`);
    if (!response.ok) throw new Error(`Failed to fetch dashboards: ${response.status}`);
    return response.json();
}

export async function createDashboard(data: NewDashboard): Promise<Dashboard> {
    const response = await fetch(`${API}/dashboards`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to create dashboard: ${response.status}`);
    return response.json();
}

export async function fetchDashboard(dashboardId: number): Promise<Dashboard> {
    const response = await fetch(`${API}/dashboards/${dashboardId}`);
    if (!response.ok) throw new Error(`Failed to fetch dashboard: ${response.status}`);
    return response.json();
}

export async function updateDashboard(dashboardId: number, data: NewDashboard): Promise<Dashboard> {
    const response = await fetch(`${API}/dashboards/${dashboardId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to update dashboard: ${response.status}`);
    return response.json();
}

export async function deleteDashboard(dashboardId: number): Promise<void> {
    const response = await fetch(`${API}/dashboards/${dashboardId}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(`Failed to delete dashboard: ${response.status}`);
}

// ── Scheduled Jobs ──────────────────────────────────────────────

export interface ScheduledJob {
    id: number;
    name: string;
    description?: string;
    slug?: string;
    labels?: string[];
    enabled: boolean;
    schedule: string;
    scheduleTime?: string;
    scheduleDayOfWeek?: string;
    nextRunAt?: string;
    lastRunAt?: string;
    executionMode: string;
    promptTemplate?: string;
    scriptTemplate?: string;
    model?: string;
    engine?: string;
    allowedTools?: string[];
    maxSteps?: number;
    maxBudgetUsd?: number;
    timeoutSeconds?: number;
    environment?: Record<string, string>;
    createdOn: string;
    updatedOn: string;
}

export type NewScheduledJob = Omit<ScheduledJob, "id" | "createdOn" | "updatedOn" | "nextRunAt" | "lastRunAt">;

export interface ScheduledJobRun {
    id: number;
    jobId: number;
    jobName?: string;
    status: string;
    trigger: string;
    startedAt?: string;
    completedAt?: string;
    output?: string;
    error?: string;
    executionLog?: string;
    costUsd?: number;
    durationMs?: number;
    traceId?: string;
    createdOn: string;
}

export async function fetchScheduledJobs(): Promise<ScheduledJob[]> {
    const response = await fetch(`${API}/scheduled-jobs`);
    if (!response.ok) throw new Error(`Failed to fetch scheduled jobs: ${response.status}`);
    return response.json();
}

export async function fetchScheduledJob(jobId: number): Promise<ScheduledJob> {
    const response = await fetch(`${API}/scheduled-jobs/${jobId}`);
    if (!response.ok) throw new Error(`Failed to fetch scheduled job: ${response.status}`);
    return response.json();
}

export async function createScheduledJob(data: NewScheduledJob): Promise<ScheduledJob> {
    const response = await fetch(`${API}/scheduled-jobs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || `Failed to create scheduled job: ${response.status}`);
    }
    return response.json();
}

export async function updateScheduledJob(jobId: number, data: NewScheduledJob): Promise<ScheduledJob> {
    const response = await fetch(`${API}/scheduled-jobs/${jobId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || `Failed to update scheduled job: ${response.status}`);
    }
    return response.json();
}

export async function deleteScheduledJob(jobId: number): Promise<void> {
    const response = await fetch(`${API}/scheduled-jobs/${jobId}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(`Failed to delete scheduled job: ${response.status}`);
}

export async function runScheduledJob(jobId: number): Promise<ScheduledJobRun> {
    const response = await fetch(`${API}/scheduled-jobs/${jobId}/run`, {
        method: "POST",
    });
    if (!response.ok) throw new Error(`Failed to trigger scheduled job: ${response.status}`);
    return response.json();
}

export async function fetchScheduledJobRuns(
    jobId: number,
    page = 1,
    limit = 20
): Promise<SearchResults<ScheduledJobRun>> {
    const response = await fetch(
        `${API}/scheduled-jobs/${jobId}/runs?page=${page}&limit=${limit}`
    );
    if (!response.ok) throw new Error(`Failed to fetch job runs: ${response.status}`);
    return response.json();
}

export async function fetchAllScheduledJobRuns(
    page = 1, limit = 20,
    filterJobName?: string, filterStatus?: string, filterTrigger?: string
): Promise<SearchResults<ScheduledJobRun>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterJobName) params.set("filterJobName", filterJobName);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterTrigger) params.set("filterTrigger", filterTrigger);
    const response = await fetch(`${API}/scheduled-jobs/runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch job runs: ${response.status}`);
    return response.json();
}

export async function fetchScheduledJobRun(runId: number): Promise<ScheduledJobRun> {
    const response = await fetch(`${API}/scheduled-jobs/runs/${runId}`);
    if (!response.ok) throw new Error(`Failed to fetch job run: ${response.status}`);
    return response.json();
}

export async function validateScheduledJob(data: NewScheduledJob): Promise<ToolValidationResult> {
    const response = await fetch(`${API}/scheduled-jobs/validate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to validate scheduled job: ${response.status}`);
    return response.json();
}

// ── Workflow Definitions ──────────────────────────────────────────

export interface WorkflowDefinition {
    id: number;
    name: string;
    description?: string;
    content?: any;
    currentVersion?: number;
    createdOn: string;
    updatedOn: string;
}

export interface NewWorkflowDefinition {
    name: string;
    description?: string;
}

export interface UpdateWorkflowDefinition {
    name?: string;
    description?: string;
}

export interface WorkflowDefinitionVersion {
    id: number;
    definitionId: number;
    version: number;
    content?: any;
    createdOn: string;
}

export async function fetchWorkflowDefinitions(
    page = 1, limit = 20, filterName?: string
): Promise<SearchResults<WorkflowDefinition>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    const response = await fetch(`${API}/workflow/definitions?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow definitions: ${response.status}`);
    return response.json();
}

export async function createWorkflowDefinition(
    data: NewWorkflowDefinition
): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow/definitions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to create workflow definition: ${response.status}`);
    return response.json();
}

export async function getWorkflowDefinition(id: number): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow/definitions/${id}`);
    if (!response.ok) throw new Error(`Failed to get workflow definition: ${response.status}`);
    return response.json();
}

export async function updateWorkflowDefinition(
    id: number, data: UpdateWorkflowDefinition
): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow/definitions/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to update workflow definition: ${response.status}`);
    return response.json();
}

export async function updateWorkflowDefinitionContent(
    id: number, content: any
): Promise<void> {
    const response = await fetch(`${API}/workflow/definitions/${id}/content`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(content),
    });
    if (!response.ok) throw new Error(`Failed to update workflow content: ${response.status}`);
}

export async function publishWorkflowDefinition(
    id: number
): Promise<WorkflowDefinitionVersion> {
    const response = await fetch(`${API}/workflow/definitions/${id}/publish`, {
        method: "POST",
    });
    if (!response.ok) throw new Error(`Failed to publish workflow definition: ${response.status}`);
    return response.json();
}

export async function deleteWorkflowDefinition(id: number): Promise<void> {
    const response = await fetch(`${API}/workflow/definitions/${id}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(`Failed to delete workflow definition: ${response.status}`);
}

export async function listWorkflowDefinitionVersions(
    id: number
): Promise<WorkflowDefinitionVersion[]> {
    const response = await fetch(`${API}/workflow/definitions/${id}/versions`);
    if (!response.ok) throw new Error(`Failed to list versions: ${response.status}`);
    return response.json();
}

export async function getWorkflowDefinitionVersion(
    id: number, version: number
): Promise<WorkflowDefinitionVersion> {
    const response = await fetch(`${API}/workflow/definitions/${id}/versions/${version}`);
    if (!response.ok) throw new Error(`Failed to get version: ${response.status}`);
    return response.json();
}

export interface WorkflowInstanceInfo {
    id: number;
    projectId: number;
    definitionId: number;
    definitionVersion: number;
    definitionName: string;
    status: string;
    currentNodeId?: string;
    currentNodeName?: string;
    failureReason?: string;
    workflowContent: any;
    context: Record<string, any>;
    history: HistoryEntryInfo[];
    startedOn: string;
    completedOn?: string;
    runId?: number;
    traceId?: string;
}

export interface HistoryEntryInfo {
    nodeId: string;
    nodeName: string;
    enteredOn: string;
    completedOn?: string;
    output?: any;
    taskId?: number;
    taskStatus?: string;
    edgeId?: string;
    edgeCondition?: string;
}

export interface TriggerWorkflowRequest {
    workflowDefinitionId: number;
}

export async function triggerWorkflow(
    projectId: number, data: TriggerWorkflowRequest
): Promise<WorkflowInstanceInfo> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        });
    if (!response.ok) {
        let message = `Failed to trigger workflow: ${response.status}`;
        try {
            const body = await response.json();
            if (body && typeof body === "object"
                    && typeof body.message === "string" && body.message) {
                message = body.message;
            } else if (typeof body === "string" && body) {
                message = body;
            }
        } catch {
            // No JSON body; keep the status-based message.
        }
        throw new Error(message);
    }
    return response.json();
}

export async function getWorkflowInstance(
    projectId: number
): Promise<WorkflowInstanceInfo> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`);
    if (!response.ok) {
        if (response.status === 404) return null as any;
        throw new Error(
            `Failed to get workflow instance: ${response.status}`);
    }
    return response.json();
}

export async function cancelWorkflow(
    projectId: number
): Promise<void> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`, {
            method: "DELETE",
        });
    if (!response.ok) {
        throw new Error(
            `Failed to cancel workflow: ${response.status}`);
    }
}

export interface WorkflowRunSummary {
    runId: number;
    projectId: number;
    projectName?: string;
    definitionId: number;
    definitionName?: string;
    definitionVersion: number;
    status: string;
    currentNodeName?: string;
    traceId?: string;
    startedOn: string;
    completedOn?: string;
}

export async function fetchWorkflowRuns(
    page = 1, limit = 20, projectId?: number, status?: string
): Promise<SearchResults<WorkflowRunSummary>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (projectId != null) params.set("projectId", String(projectId));
    if (status) params.set("status", status);
    const response = await fetch(`${API}/workflow/runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow runs: ${response.status}`);
    return response.json();
}

export async function getWorkflowRun(runId: number): Promise<WorkflowInstanceInfo> {
    const response = await fetch(`${API}/workflow/runs/${runId}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow run: ${response.status}`);
    return response.json();
}

export async function fetchWorkflowDefinitionRuns(
    definitionId: number, page = 1, limit = 20, status?: string
): Promise<SearchResults<WorkflowRunSummary>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (status) params.set("status", status);
    const response = await fetch(
        `${API}/workflow/definitions/${definitionId}/runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch definition runs: ${response.status}`);
    return response.json();
}
