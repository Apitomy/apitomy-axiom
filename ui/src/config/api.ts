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

export interface SystemConfig {
    version: string;
    engine?: string;
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
    description?: string;
    type: string;
    status: string;
    issueSource: string;
    issueRef: string;
    repository: string;
    createdOn: string;
    updatedOn: string;
    metadata?: Record<string, string>;
    labels?: string[];
}

export interface NewProject {
    name: string;
    description?: string;
    type: string;
    issueSource: string;
    issueRef: string;
    repository: string;
}

export interface Task {
    id: number;
    projectId: number;
    eventId?: number;
    actionType: string;
    createdBy: string;
    assignedActor?: number;
    status: string;
    input?: string;
    output?: string;
    createdOn: string;
    completedOn?: string;
    sessionId?: string;
    traceId?: string;
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
}

export interface ImportResult {
    actionTypes?: number;
    tools?: number;
    toolsets?: number;
    mcpServers?: number;
    reportDefinitions?: number;
}

export interface AssistantApplyResult {
    tools?: number;
    actionTypes?: number;
    reportDefinitions?: number;
    toolsets?: number;
    sessionTemplates?: number;
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
    assignedActor?: number;
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

export async function respondToTask(
    projectId: number,
    taskId: number,
    response: string
): Promise<void> {
    const res = await fetch(
        `${API}/projects/${projectId}/tasks/${taskId}/respond`,
        {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ response }),
        }
    );
    if (!res.ok) throw new Error(`Failed to respond to task: ${res.status}`);
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

export interface ActionType {
    id: number;
    name: string;
    description?: string;
    executionMode: string;
    userTriggerable: boolean;
    managerTriggerable: boolean;
    inputSchema?: string;
    allowedTools?: string[];
    promptTemplate?: string;
    scriptTemplate?: string;
    model?: string;
    engine?: string;
    emitsEvent: boolean;
    environment?: Record<string, string>;
}

export type NewActionType = Omit<ActionType, "id">;

export async function fetchActionTypes(): Promise<ActionType[]> {
    const response = await fetch(`${API}/action-types`);
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
    if (!response.ok) throw new Error(`Failed to create action type: ${response.status}`);
    return response.json();
}

export async function updateActionType(id: number, at: NewActionType): Promise<ActionType> {
    const response = await fetch(`${API}/action-types/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(at),
    });
    if (!response.ok) throw new Error(`Failed to update action type: ${response.status}`);
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

// ── Actors ────────────────────────────────────────────────────────

export interface Actor {
    id: number;
    name: string;
    description?: string;
    type: string;
    capabilities?: string[];
}

export type NewActor = Omit<Actor, "id">;

export async function fetchActors(): Promise<Actor[]> {
    const response = await fetch(`${API}/actors`);
    if (!response.ok) throw new Error(`Failed to fetch actors: ${response.status}`);
    return response.json();
}

export async function createActor(actor: NewActor): Promise<Actor> {
    const response = await fetch(`${API}/actors`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(actor),
    });
    if (!response.ok) throw new Error(`Failed to create actor: ${response.status}`);
    return response.json();
}

export async function updateActor(id: number, actor: NewActor): Promise<Actor> {
    const response = await fetch(`${API}/actors/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(actor),
    });
    if (!response.ok) throw new Error(`Failed to update actor: ${response.status}`);
    return response.json();
}

export async function deleteActor(id: number): Promise<void> {
    const response = await fetch(`${API}/actors/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error(`Failed to delete actor: ${response.status}`);
}

export async function fetchActor(id: number): Promise<Actor> {
    const response = await fetch(`${API}/actors/${id}`);
    if (!response.ok) throw new Error(`Failed to fetch actor: ${response.status}`);
    return response.json();
}

export async function fetchActorTasks(
    actorId: number, page = 1, limit = 20,
    filterActionType?: string, filterStatus?: string
): Promise<SearchResults<Task>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterStatus) params.set("filterStatus", filterStatus);
    const response = await fetch(`${API}/actors/${actorId}/tasks?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch actor tasks: ${response.status}`);
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
}

export type NewEventSource = Omit<EventSource, "id">;

export async function fetchEventSources(): Promise<EventSource[]> {
    const response = await fetch(`${API}/event-sources`);
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

export interface EventSourceLog {
    id: number;
    eventSourceId: number;
    status: string;
    message: string;
    detail?: string;
    eventsIngested?: number;
    createdOn: string;
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
    schedule: string;
    scheduleTime?: string;
    scheduleDayOfWeek?: string;
    timeWindow: string;
    promptTemplate: string;
    titleTemplate?: string;
    allowedTools?: string[];
    enabled: boolean;
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
    actorId?: number;
    actionType?: string;
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
    filterActorId?: number, filterActionType?: string,
    filterDateFrom?: string, filterDateTo?: string
): Promise<AiUsageSearchResults> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterInvocationType) params.set("filterInvocationType", filterInvocationType);
    if (filterProjectId != null) params.set("filterProjectId", String(filterProjectId));
    if (filterActorId != null) params.set("filterActorId", String(filterActorId));
    if (filterActionType) params.set("filterActionType", filterActionType);
    if (filterDateFrom) params.set("filterDateFrom", filterDateFrom);
    if (filterDateTo) params.set("filterDateTo", filterDateTo);
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
    page = 1, limit = 20, filterName?: string
): Promise<DiskUsageSearchResults> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
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
    filterSource?: string, filterEventType?: string, filterRepository?: string
): Promise<SearchResults<AxiomEvent>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterSource) params.set("filterSource", filterSource);
    if (filterEventType) params.set("filterEventType", filterEventType);
    if (filterRepository) params.set("filterRepository", filterRepository);
    const response = await fetch(`${API}/events?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch events: ${response.status}`);
    return response.json();
}

// ── Activity Log ──────────────────────────────────────────────────

export async function fetchActivityLog(
    page = 1, limit = 20,
    filterEventId?: number, filterSummary?: string,
    filterProjectId?: number, filterEntryType?: string
): Promise<SearchResults<ActivityLogEntry>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterEventId != null) params.set("filterEventId", String(filterEventId));
    if (filterSummary) params.set("filterSummary", filterSummary);
    if (filterProjectId != null) params.set("filterProjectId", String(filterProjectId));
    if (filterEntryType) params.set("filterEntryType", filterEntryType);
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
}

export interface AssistantItem {
    type: string;
    name: string;
    valid: boolean;
    validationErrors?: string[];
}

export async function createAssistantSession(
    templateId: string,
    name?: string
): Promise<AssistantSessionInfo> {
    const response = await fetch(`${API}/assistant/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, templateId }),
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

export async function fetchAssistantItems(sessionId: string): Promise<AssistantItem[]> {
    const response = await fetch(`${API}/assistant/sessions/${sessionId}/items`);
    if (!response.ok) throw new Error(`Failed to fetch items: ${response.status}`);
    return response.json();
}

export async function fetchAssistantItemContent(
    sessionId: string, itemType: string, itemName: string
): Promise<Record<string, unknown>> {
    const response = await fetch(
        `${API}/assistant/sessions/${sessionId}/items/${itemType}/${itemName}`
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

export async function fetchInboxItems(page = 1, limit = 20): Promise<SearchResults<InboxItem>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
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
