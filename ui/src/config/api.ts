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
export async function extractErrorMessage(response: Response, fallback: string): Promise<string> {
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
    modelSource?: "configured" | "dynamic";
}

export interface SystemConfig {
    version: string;
    defaultEngine?: string;
    engines?: EngineInfo[];
    features: Record<string, boolean>;
    checks?: StartupCheck[];
}

export interface UpdateSystemConfigRequest {
    defaultEngine: string;
}

export interface SearchResults<T> {
    items: T[];
    totalCount: number;
    page: number;
    limit: number;
}

export interface Subject {
    id: number;
    name: string;
    body?: string;
    type: string;
    refSource?: string;
    ref: string;
    repository?: string;
    createdOn: string;
    updatedOn: string;
    metadata?: Record<string, string>;
    labels?: string[];
    hasWorkflowInstance?: boolean;
    /** True if the subject has at least one running or waiting workflow run. */
    active?: boolean;
}

export interface NewSubject {
    name: string;
    body?: string;
    type: string;
    refSource?: string;
    ref: string;
    repository?: string;
}

export interface Task {
    id: number;
    subjectId: number;
    eventId?: string;
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
    subjectId: number;
    authorType: string;
    authorId?: string;
    entryType: string;
    content: string;
    createdOn: string;
}

export interface ActivityLogEntry {
    id: number;
    subjectId?: number;
    taskId?: number;
    eventId?: string;
    traceId?: string;
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

export async function updateSystemConfig(
    data: UpdateSystemConfigRequest
): Promise<SystemConfig> {
    const response = await fetch(`${API}/system/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        const message = await extractErrorMessage(response, "Failed to update system config");
        throw new Error(message);
    }
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
    sessionTemplateIds?: string[];
    eventSourceIds?: string[];
    subscriptionIds?: number[];
    workflowDefinitionIds?: number[];
}

export interface ImportResult {
    actionTypes?: number;
    tools?: number;
    toolsets?: number;
    mcpServers?: number;
    sessionTemplates?: number;
    eventSources?: number;
    subscriptions?: number;
    workflowDefinitions?: number;
}

export interface AssistantApplyResult {
    tools?: number;
    actionTypes?: number;
    toolsets?: number;
    sessionTemplates?: number;
    toolsCreated?: number;
    toolsUpdated?: number;
    actionTypesCreated?: number;
    actionTypesUpdated?: number;
    toolsetsCreated?: number;
    toolsetsUpdated?: number;
    sessionTemplatesCreated?: number;
    sessionTemplatesUpdated?: number;
    eventSourcesCreated?: number;
    eventSourcesUpdated?: number;
    subscriptionsCreated?: number;
    subscriptionsUpdated?: number;
    workflowDefinitionsCreated?: number;
    workflowDefinitionsUpdated?: number;
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

/** A single validation problem reported for a configuration item. */
export interface PackImportProblem {
    severity: string;
    path: string;
    code: string;
    message: string;
}

/** Error thrown by {@link importPack} when the pack fails validation (HTTP 400). */
export interface PackImportValidationError {
    status: 400;
    message?: string;
    problems: Record<string, PackImportProblem[]>;
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
        if (response.status === 400 && body.problems) {
            throw { status: 400, message: body.message, problems: body.problems } as PackImportValidationError;
        }
        throw new Error(body.message || `Failed to import pack: ${response.status}`);
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

// ── Subjects ──────────────────────────────────────────────────────

export async function fetchSubjects(
    page = 1, limit = 20, filterName?: string, filterActive?: boolean, filterLabels?: string
): Promise<SearchResults<Subject>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterActive !== undefined) params.set("filterActive", String(filterActive));
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/subjects?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch subjects: ${response.status}`);
    return response.json();
}

export async function fetchSubject(id: number): Promise<Subject> {
    const response = await fetch(`${API}/subjects/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch subject: ${response.status}`);
    return response.json();
}

export async function updateSubject(id: number, data: Partial<Subject>): Promise<Subject> {
    const response = await fetch(`${API}/subjects/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to update subject: ${response.status}`);
    return response.json();
}

export async function createSubject(subject: NewSubject): Promise<Subject> {
    const response = await fetch(`${API}/subjects`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(subject),
    });
    if (!response.ok) throw new Error(`Failed to create subject: ${response.status}`);
    return response.json();
}

export async function deleteSubject(id: number): Promise<void> {
    const response = await fetch(`${API}/subjects/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete subject: ${response.status}`);
}

export async function updateSubjectBody(id: number, body: string): Promise<void> {
    const response = await fetch(`${API}/subjects/${id}/body`, {
        method: "PUT",
        headers: { "Content-Type": "text/markdown" },
        body,
    });
    if (!response.ok) throw new Error(`Failed to update subject body: ${response.status}`);
}

// ── Tasks ─────────────────────────────────────────────────────────

export async function fetchAllTasks(
    page = 1, limit = 20,
    filterActionType?: string, filterStatus?: string, filterSubjectId?: number
): Promise<SearchResults<Task>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterSubjectId != null) params.set("filterSubjectId", String(filterSubjectId));
    const response = await fetch(`${API}/tasks?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch tasks: ${response.status}`);
    return response.json();
}

export async function cancelTask(subjectId: number, taskId: number): Promise<Task> {
    const response = await fetch(`${API}/subjects/${subjectId}/tasks/${taskId}/cancel`, {
        method: "POST",
    });
    if (!response.ok) throw new Error(`Failed to cancel task: ${response.status}`);
    return response.json();
}

export async function fetchSubjectTasks(subjectId: number): Promise<Task[]> {
    const response = await fetch(`${API}/subjects/${subjectId}/tasks`);
    if (!response.ok) throw new Error(`Failed to fetch tasks: ${response.status}`);
    return response.json();
}

export async function fetchTaskExecutionLog(
    subjectId: number,
    taskId: number
): Promise<string> {
    const response = await fetch(
        `${API}/subjects/${subjectId}/tasks/${taskId}/log`
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
    allowedTools?: string[];
    promptTemplate?: string;
    scriptTemplate?: string;
    codeTemplate?: string;
    model?: string;
    engine?: string;
    maxSteps?: number;
    maxBudgetUsd?: number;
    timeoutSeconds?: number;
    environment?: Record<string, string>;
    labels?: string[];
    inputs?: ActionTypeField[];
    outputs?: ActionTypeField[];
}

export type NewActionType = Omit<ActionType, "id">;

export async function fetchActionTypes(
    page = 1, limit = 20, filterName?: string, filterMode?: string,
    filterLabels?: string
): Promise<SearchResults<ActionType>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterMode) params.set("filterMode", filterMode);
    if (filterLabels) params.set("filterLabels", filterLabels);
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
    environment?: Record<string, string>;
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

// ── Retention Configuration ─────────────────────────────────────

export interface RetentionConfig {
    inactiveSubjectRetentionDays?: number;
    traceRetentionDays?: number;
    eventRetentionDays?: number;
    /** Days to keep finished workflow runs; 0 keeps them forever. */
    workflowRunRetentionDays?: number;
    /** Days to keep activity log entries; 0 keeps them forever. */
    activityLogRetentionDays?: number;
    /** Days to keep AI usage records; 0 keeps them forever. */
    aiUsageRetentionDays?: number;
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
    if (!response.ok) {
        const detail = (await response.text()).trim();
        throw new Error(`Failed to update retention config (${response.status})`
            + (detail ? `: ${detail}` : ""));
    }
    return response.json();
}

// ── Event Source Filters (used by subscriptions) ─────────────────

export interface EventSourceFilterRule {
    type: "event-type" | "payload";
    pointer?: string;
    pattern: string;
}

export interface EventSourceFilters {
    include: EventSourceFilterRule[];
    exclude: EventSourceFilterRule[];
}

// ── Thread ────────────────────────────────────────────────────────

export async function fetchThreadEntries(subjectId: number): Promise<ThreadEntry[]> {
    const response = await fetch(`${API}/subjects/${subjectId}/thread`);
    if (!response.ok) throw new Error(`Failed to fetch thread: ${response.status}`);
    return response.json();
}

// ── Metrics ──────────────────────────────────────────────────────

export interface SubjectMetrics {
    subjectId: number;
    diskUsageBytes: number;
    totalCostUsd: number;
    totalInputTokens: number;
    totalOutputTokens: number;
    invocationCount: number;
}

export async function fetchSubjectMetrics(subjectId: number): Promise<SubjectMetrics> {
    const response = await fetch(`${API}/subjects/${subjectId}/metrics`);
    if (!response.ok) throw new Error(`Failed to fetch subject metrics: ${response.status}`);
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
    eventId?: string;
    traceId?: string;
    subjectId?: number;
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
    filterInvocationType?: string, filterSubjectId?: number,
    filterAgentId?: number, filterActionType?: string,
    filterDateFrom?: string, filterDateTo?: string,
    filterLabels?: string, filterEngine?: string,
    filterModel?: string, filterTraceId?: string
): Promise<AiUsageSearchResults> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterInvocationType) params.set("filterInvocationType", filterInvocationType);
    if (filterSubjectId != null) params.set("filterSubjectId", String(filterSubjectId));
    if (filterAgentId != null) params.set("filterAgentId", String(filterAgentId));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterDateFrom) params.set("filterDateFrom", filterDateFrom);
    if (filterDateTo) params.set("filterDateTo", filterDateTo);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterEngine) params.set("filterEngine", filterEngine);
    if (filterModel) params.set("filterModel", filterModel);
    if (filterTraceId) params.set("filterTraceId", filterTraceId);
    const response = await fetch(`${API}/usage/ai?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch usage: ${response.status}`);
    return response.json();
}

// ── Disk Usage ──────────────────────────────────────────────────

export interface DiskUsageSubject {
    subjectId: number;
    subjectName: string;
    diskUsageBytes: number;
}

export interface DiskUsageSearchResults extends SearchResults<DiskUsageSubject> {
    totalDiskUsageBytes: number;
    subjectCount: number;
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

// ── Activity Log ──────────────────────────────────────────────────

export async function fetchActivityLog(
    page = 1, limit = 20,
    filterEventId?: string, filterSummary?: string,
    filterSubjectId?: number, filterEntryType?: string,
    filterLabels?: string, filterTraceId?: string
): Promise<SearchResults<ActivityLogEntry>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterEventId) params.set("filterEventId", String(filterEventId));
    if (filterSummary) params.set("filterSummary", filterSummary);
    if (filterSubjectId != null) params.set("filterSubjectId", String(filterSubjectId));
    if (filterEntryType) params.set("filterEntryType", filterEntryType);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterTraceId) params.set("filterTraceId", filterTraceId);
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
    capabilities?: string[];
    systemPrompt: string;
    welcomeMessage?: string;
    initialMessage?: string;
    workingDirectory?: string;
    model?: string;
    engine?: string;
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
    engine?: string;
    initScript?: string;
    initScriptType?: string;
    environment?: Record<string, string>;
    mcpServers?: string[];
    allowedTools?: string[];
}

export interface AssistantSessionInfo {
    capabilities?: string[];
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
    subjectId?: number;
    subjectName?: string;
    allowAll?: boolean;
    engine?: string;
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
    subjectId?: number
): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, templateId, subjectId: subjectId }),
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
    updatedInput?: Record<string, unknown>, always?: boolean
): Promise<void> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/permissions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ permissionId, allow, updatedInput, ...(always ? { always: true } : {}) }),
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
    eventId?: string;
    subjectId?: number;
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
    entityId?: string;
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
    filterEventId?: string, filterSubjectId?: number
): Promise<SearchResults<Trace>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterTraceType) params.set("filterTraceType", filterTraceType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    if (filterEventId) params.set("filterEventId", String(filterEventId));
    if (filterSubjectId) params.set("filterSubjectId", String(filterSubjectId));
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

export async function fetchSubjectTraces(subjectId: number): Promise<Trace[]> {
    const response = await fetch(`${API}/subjects/${subjectId}/traces`);
    if (!response.ok) throw new Error(`Failed to fetch subject traces: ${response.status}`);
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
    subjectId: number;
    subjectName?: string;
    actionType: string;
    status: string;
    input?: string;
    humanContext?: HumanContext;
    outputSchema?: OutputSchema;
    createdOn: string;
    eventId?: string;
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
    subjectId: number;
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
    if (!response.ok) {
        let detail = `${response.status}`;
        try {
            const body = await response.json();
            if (Array.isArray(body)) {
                detail = body.map((p: { code?: string; message?: string }) => p.message || p.code || JSON.stringify(p)).join("; ");
            } else if (body.message) {
                detail = body.message;
            } else {
                detail = JSON.stringify(body);
            }
        } catch { /* no parseable body */ }
        throw new Error(detail);
    }
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

export interface ActiveBranchInfo {
    branchId?: string;
    nodeId?: string;
    nodeName?: string;
}

export interface WorkflowInstanceInfo {
    id: number;
    subjectId: number;
    /** Name of the subject the run belongs to. */
    subjectName?: string;
    definitionId: number;
    definitionVersion: number;
    definitionName: string;
    /** Title the run was started with; absent when none was given. */
    title?: string;
    labels?: Record<string, string>;
    inputs?: Record<string, unknown>;
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
    activeBranches?: ActiveBranchInfo[];
    /** Stream event whose create-workflow routing started this run, if any. */
    triggerEventId?: string;
    triggerLedgerId?: number;
    /** Stream events that resumed this run at receive-event nodes, oldest first. */
    resumedBy?: WorkflowRunResumeInfo[];
}

export interface WorkflowRunResumeInfo {
    eventId: string;
    ledgerId?: number;
    nodeId: string;
    nodeName?: string;
    traceNodeId?: number;
    resumedOn: string;
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
    branchId?: string;
    /** Inputs the node received on this visit. */
    input?: Record<string, unknown>;
    /** The computed title of a human-task node on this visit. */
    title?: string;
}

/**
 * Re-runs a failed workflow run: starts a new run of the same workflow (latest published version) for the same
 * subject, with the original title and labels (plus rerunOf).
 * @param runId the failed run
 * @returns the new run
 */
export async function rerunWorkflowRun(runId: number): Promise<WorkflowInstanceInfo> {
    const response = await fetch(`${API}/workflow/runs/${runId}/rerun`, { method: "POST" });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to re-run workflow run"));
    }
    return response.json();
}

export async function cancelWorkflowRun(
    runId: number
): Promise<void> {
    const response = await fetch(
        `${API}/workflow/runs/${runId}/cancel`, {
            method: "POST",
        });
    if (!response.ok) {
        throw new Error(
            `Failed to cancel workflow: ${response.status}`);
    }
}

export interface WorkflowRunSummary {
    runId: number;
    subjectId: number;
    subjectName?: string;
    definitionId: number;
    definitionName?: string;
    definitionVersion: number;
    /** Title the run was started with; absent when none was given. */
    title?: string;
    labels?: Record<string, string>;
    inputs?: Record<string, unknown>;
    status: string;
    currentNodeName?: string;
    traceId?: string;
    triggerEventId?: string;
    triggerLedgerId?: number;
    startedOn: string;
    completedOn?: string;
}

/** Optional filters for listing workflow runs. */
export interface WorkflowRunFilters {
    subjectId?: number;
    /** Comma-separated statuses. */
    status?: string;
    definitionId?: number;
    /** Labels in `key:value` form; all must match. */
    labels?: string[];
    /** Id (UUID) of the stream event that started the runs. */
    triggerEventId?: string;
}

export async function fetchWorkflowRuns(
    page = 1, limit = 20, filters: WorkflowRunFilters = {}
): Promise<SearchResults<WorkflowRunSummary>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filters.subjectId != null) params.set("subjectId", String(filters.subjectId));
    if (filters.status) params.set("status", filters.status);
    if (filters.definitionId != null) params.set("definitionId", String(filters.definitionId));
    (filters.labels ?? []).forEach((l) => params.append("label", l));
    if (filters.triggerEventId) params.set("triggerEventId", filters.triggerEventId);
    const response = await fetch(`${API}/workflow/runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow runs: ${response.status}`);
    return response.json();
}

export async function getWorkflowRun(runId: number): Promise<WorkflowInstanceInfo> {
    const response = await fetch(`${API}/workflow/runs/${runId}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow run: ${response.status}`);
    return response.json();
}

/** Request body for manually running a workflow from a stream event. */
export interface TriggerStreamEventWorkflowRequest {
    workflowDefinitionId: number;
    title?: string;
    labels?: Record<string, string>;
}

/** A workflow suggested for a stream event because a subscription matching the event uses it. */
export interface StreamEventWorkflowSuggestion {
    workflowDefinitionId: number;
    workflowName: string;
    subscriptionId: number;
    subscriptionName: string;
}

/**
 * Manually starts a workflow run on the subject of a stream event, with the event in `context.event`.
 *
 * @param eventId the stream event id (UUID)
 * @param data the workflow and optional title and labels
 * @returns the started workflow instance
 */
export async function triggerStreamEventWorkflow(
    eventId: string, data: TriggerStreamEventWorkflowRequest
): Promise<WorkflowInstanceInfo> {
    const response = await fetch(`${API}/stream/events/${encodeURIComponent(eventId)}/runs`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to run workflow"));
    }
    return response.json();
}

/**
 * Lists the workflows used by subscriptions that match a stream event.
 *
 * @param eventId the stream event id (UUID)
 * @returns the suggested workflows
 */
export async function fetchStreamEventWorkflowSuggestions(
    eventId: string
): Promise<StreamEventWorkflowSuggestion[]> {
    const response = await fetch(
        `${API}/stream/events/${encodeURIComponent(eventId)}/workflow-suggestions`);
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to fetch workflow suggestions"));
    }
    return response.json();
}

// ── Schedule Triggers ─────────────────────────────────────────────

export interface NewScheduleTrigger {
    /** Unix (5-field) cron expression, evaluated in UTC. */
    cron: string;
    subjectName: string;
    enabled?: boolean;
}

export interface ScheduleTrigger extends NewScheduleTrigger {
    id: number;
    definitionId: number;
    enabled: boolean;
    nextRunAt?: string;
    lastRunAt?: string;
    /** started, skipped or "failed: <reason>". */
    lastOutcome?: string;
    createdOn?: string;
    updatedOn?: string;
}

export async function fetchScheduleTriggers(definitionId: number): Promise<ScheduleTrigger[]> {
    const response = await fetch(`${API}/workflow/definitions/${definitionId}/triggers/schedules`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch schedule triggers"));
    return response.json();
}

export async function createScheduleTrigger(
    definitionId: number, data: NewScheduleTrigger
): Promise<ScheduleTrigger> {
    const response = await fetch(`${API}/workflow/definitions/${definitionId}/triggers/schedules`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to create schedule trigger"));
    return response.json();
}

export async function updateScheduleTrigger(
    definitionId: number, triggerId: number, data: NewScheduleTrigger
): Promise<ScheduleTrigger> {
    const response = await fetch(
        `${API}/workflow/definitions/${definitionId}/triggers/schedules/${triggerId}`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to update schedule trigger"));
    return response.json();
}

export async function deleteScheduleTrigger(definitionId: number, triggerId: number): Promise<void> {
    const response = await fetch(
        `${API}/workflow/definitions/${definitionId}/triggers/schedules/${triggerId}`, {
            method: "DELETE",
        });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to delete schedule trigger"));
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

// ── Event Sources ───────────────────────────────────────────────────

export interface EventSourceConfig {
    id: string;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    baseUrl: string;
    secretName?: string;
    pollInterval?: number;
    configuration?: Record<string, unknown>;
    createdOn?: string;
    modifiedOn?: string;
}

export interface NewEventSource {
    id: string;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    baseUrl: string;
    secretName?: string;
    pollInterval?: number;
    configuration?: Record<string, unknown>;
}

export interface EventSourceStatus {
    eventSourceId: string;
    enabled: boolean;
    lastPolledAt?: string;
    totalEventsProduced: number;
    lastError?: string;
    lastErrorAt?: string;
}

export async function fetchEventSources(
    page = 1, limit = 20, filterName?: string, filterType?: string
): Promise<SearchResults<EventSourceConfig>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterType) params.set("filterType", filterType);
    const response = await fetch(`${API}/event-sources?${params}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event sources"));
    return response.json();
}

export async function fetchEventSource(eventSourceId: string): Promise<EventSourceConfig> {
    const response = await fetch(`${API}/event-sources/${encodeURIComponent(eventSourceId)}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event source"));
    return response.json();
}

export async function createEventSource(data: NewEventSource): Promise<EventSourceConfig> {
    const response = await fetch(`${API}/event-sources`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to create event source"));
    return response.json();
}

export async function updateEventSource(eventSourceId: string, data: NewEventSource): Promise<EventSourceConfig> {
    const response = await fetch(`${API}/event-sources/${encodeURIComponent(eventSourceId)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to update event source"));
    return response.json();
}

export async function deleteEventSource(eventSourceId: string): Promise<void> {
    const response = await fetch(`${API}/event-sources/${encodeURIComponent(eventSourceId)}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to delete event source"));
}

export async function fetchEventSourceStatus(eventSourceId: string): Promise<EventSourceStatus> {
    const response = await fetch(`${API}/event-sources/${encodeURIComponent(eventSourceId)}/status`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event source status"));
    return response.json();
}

export interface EventSourcePollLog {
    id: number;
    eventSourceId: string;
    status: string;
    message: string;
    detail?: string;
    eventsIngested?: number;
    durationMs?: number;
    createdOn?: string;
}

export async function fetchEventSourcePollLogs(
    eventSourceId: string, page = 1, limit = 20
): Promise<SearchResults<EventSourcePollLog>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    const response = await fetch(
        `${API}/event-sources/${encodeURIComponent(eventSourceId)}/logs?${params}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch poll logs"));
    return response.json();
}

// ── Stream Events ─────────────────────────────────────────────────

export interface StreamEvent {
    id: string;
    sourceEventId?: string;
    source: string;
    eventSourceId: string;
    type: string;
    ref: string;
    timestamp: string;
    actor?: Record<string, unknown>;
    payload?: Record<string, unknown>;
    sourceData?: Record<string, unknown>;
    createdOn?: string;
    /** Subscriptions whose filter matched (every ledger state except skipped). */
    matchedSubscriptions?: number;
    /** Per-state ledger counts; absent when the event has no ledger entries. */
    processingStates?: StreamEventProcessingStates;
}

/** Counts of an event's event/subscription pairs in the processing ledger, by state. */
export interface StreamEventProcessingStates {
    pending?: number;
    completed?: number;
    failed?: number;
    exhausted?: number;
    skipped?: number;
}

export async function fetchStreamEvents(
    page = 1, limit = 20,
    filterType?: string, filterEventSourceId?: string, filterRef?: string, filterActor?: string
): Promise<SearchResults<StreamEvent>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterType) params.set("filterType", filterType);
    if (filterEventSourceId) params.set("filterEventSourceId", filterEventSourceId);
    if (filterRef) params.set("filterRef", filterRef);
    if (filterActor) params.set("filterActor", filterActor);
    const response = await fetch(`${API}/stream/events?${params}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch stream events"));
    return response.json();
}

export async function fetchStreamEvent(eventId: string): Promise<StreamEvent> {
    const response = await fetch(`${API}/stream/events/${encodeURIComponent(eventId)}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch stream event"));
    return response.json();
}

export interface EventProcessingOutcome {
    /** completed or failed. */
    status?: string;
    /** Ledger attempt (1-based) that recorded this outcome. */
    attemptNumber?: number;
    errorMessage?: string;
    type: string;
    summary: string;
    subjectId?: number;
    subjectName?: string;
    taskId?: number;
    taskStatus?: string;
    traceId?: string;
    /** Every result of the outcome; empty for outcomes recorded before items existed. */
    items?: EventProcessingOutcomeItem[];
}

export interface EventProcessingOutcomeItem {
    type: string;
    /** completed, failed, or skipped (subject already had an active run of the definition). */
    status: "completed" | "failed" | "skipped" | string;
    summary?: string;
    errorMessage?: string;
    subjectId?: number;
    subjectName?: string;
    taskId?: number;
    taskStatus?: string;
    workflowRunId?: number;
    traceNodeId?: number;
    /** Trace of the workflow run (workflow-run and workflow-resumed items). */
    traceId?: string;
}

export interface EventProcessingEntry {
    id: number;
    subscriptionId: number;
    subscriptionName: string;
    status: string;
    errorMessage?: string;
    routingRules?: RoutingRule[];
    outcomes?: EventProcessingOutcome[];
    createdOn?: string;
    processedOn?: string;
    /** Routing attempts made so far, the first try included. */
    attemptCount?: number;
    /** Configured maximum attempts (axiom.stream-pipeline.max-attempts). */
    maxAttempts?: number;
    lastAttemptAt?: string;
    /** When a failed entry is retried next; absent for every other status. */
    nextAttemptAt?: string;
}

/** Makes a failed, exhausted or filter_error processing entry due for retry (one more attempt if exhausted). */
export async function retryEventProcessing(eventId: string, ledgerId: number): Promise<EventProcessingEntry> {
    const response = await fetch(
        `${API}/stream/events/${encodeURIComponent(eventId)}/processing/${ledgerId}/retry`,
        { method: "POST" });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to retry event processing"));
    return response.json();
}

export async function fetchEventProcessing(eventId: string): Promise<{
    items: EventProcessingEntry[];
    totalCount: number;
}> {
    const response = await fetch(
        `${API}/stream/events/${encodeURIComponent(eventId)}/processing`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch event processing"));
    return response.json();
}

// ── Subscriptions ─────────────────────────────────────────────────

export interface RoutingRule {
    type: "workflow-dispatch" | "create-workflow";
    workflowDefinitionId?: number;
    workflowDefinitionName?: string;
}

export interface Subscription {
    id: number;
    name: string;
    description?: string;
    enabled: boolean;
    filterExpression?: string;
    routing?: RoutingRule[];
    labels?: string[];
    processEventsFrom?: string;
    createdOn?: string;
    modifiedOn?: string;
}

export interface NewSubscription {
    name: string;
    description?: string;
    enabled: boolean;
    filterExpression?: string;
    routing?: RoutingRule[];
    labels?: string[];
    processEventsFrom?: string;
}

export async function fetchSubscriptions(
    page = 1, limit = 20, filterName?: string
): Promise<SearchResults<Subscription>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    const response = await fetch(`${API}/subscriptions?${params}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch subscriptions"));
    return response.json();
}

export async function fetchSubscription(subscriptionId: number): Promise<Subscription> {
    const response = await fetch(`${API}/subscriptions/${subscriptionId}`);
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to fetch subscription"));
    return response.json();
}

export async function createSubscription(data: NewSubscription): Promise<Subscription> {
    const response = await fetch(`${API}/subscriptions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to create subscription"));
    return response.json();
}

export async function updateSubscription(subscriptionId: number, data: NewSubscription): Promise<Subscription> {
    const response = await fetch(`${API}/subscriptions/${subscriptionId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to update subscription"));
    return response.json();
}

export async function deleteSubscription(subscriptionId: number): Promise<void> {
    const response = await fetch(`${API}/subscriptions/${subscriptionId}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to delete subscription"));
}

export interface SubscriptionPreviewRequest {
    filterExpression: string;
    page?: number;
    limit?: number;
    processEventsFrom?: string;
}

export interface SubscriptionPreviewResult {
    event: StreamEvent;
    matched: boolean;
}

export interface SubscriptionPreviewResponse {
    results: SubscriptionPreviewResult[];
    totalCount: number;
    totalMatched: number;
    page: number;
    limit: number;
    errorCount?: number;
    firstError?: string;
}

export async function previewSubscriptionFilter(
    data: SubscriptionPreviewRequest
): Promise<SubscriptionPreviewResponse> {
    const response = await fetch(`${API}/subscriptions/preview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(await extractErrorMessage(response, "Failed to preview filter"));
    return response.json();
}

// ── Lineage (#430) ───────────────────────────────────────────────────

/** Root entity types accepted by `GET /lineage/{entityType}/{id}`. */
export type LineageEntityType =
    "event" | "trace" | "workflow-run" | "task";

export interface LineageNode {
    key: string;
    type: string;
    id: string;
    label: string;
    status?: string;
    subtype?: string;
    linkPath?: string;
    costUsd?: number;
    direction: "root" | "upstream" | "downstream";
    depth?: number;
    available: boolean;
    createdOn?: string;
}

export interface LineageEdge {
    from: string;
    to: string;
    relation: string;
}

export interface LineageGraph {
    root: string;
    nodes: LineageNode[];
    edges: LineageEdge[];
    truncated: boolean;
    depth?: number;
    maxNodes?: number;
    totalCostUsd?: number;
}

/** Fetches the upstream ("origin") or downstream ("results") lineage graph of an entity. */
export async function fetchLineage(entityType: LineageEntityType, id: string | number,
    direction: "upstream" | "downstream" | "both" = "both"): Promise<LineageGraph> {
    const response = await fetch(
        `${API}/lineage/${entityType}/${encodeURIComponent(String(id))}?direction=${direction}`);
    if (!response.ok) throw new Error(`Failed to fetch lineage: ${response.status}`);
    return response.json();
}
