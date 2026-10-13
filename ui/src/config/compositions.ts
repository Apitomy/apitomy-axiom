import type { ChangeSet } from "@apitomy/flow-ui";
import { extractErrorMessage, getApiBaseUrl } from "./api";
import { sseClient } from "./sse";

export type { ChangeSet };

const API = `${getApiBaseUrl()}/api/v1`;

// ── Types ─────────────────────────────────────────────────────────

/** The kind of item a composition draft holds. */
export type CompositionItemType = "workflow" | "action-type" | "tool";

/** Who authored a composition item revision. */
export type CompositionAuthor = "user" | "ai";

/** A workbench composition (mirrors the OpenAPI `Composition` schema). */
export interface Composition {
    id: number;
    name: string;
    goal?: string;
    engine?: string;
    model?: string;
    assistantSessionId?: string;
    turnState: "idle" | "ai-working";
    applyMode: "live" | "review";
    createdAt: string;
    updatedAt: string;
    currentTurnId?: string;
    pendingTurnId?: string;
    pendingChangeSet?: Record<string, unknown>;
    /** Engine and model of the running assistant session, when there is one. */
    session?: CompositionSessionInfo;
}

/** Engine and model of a composition's running assistant session (mirrors `CompositionSessionInfo`). */
export interface CompositionSessionInfo {
    engine?: string;
    engineLabel?: string;
    /** Absent when the engine uses its default model. */
    model?: string;
}

/** A draft item in a composition (mirrors `CompositionItem`). */
export interface CompositionItem {
    id: number;
    type: CompositionItemType;
    name: string;
    contentRevision: string;
    baseItemId?: number;
    lastAuthor: CompositionAuthor;
    lastTurnId?: string;
    publishedRevision?: string;
    publishedAt?: string;
    changed: boolean;
    content?: Record<string, unknown>;
}

/** A historical revision of a composition item (mirrors `CompositionRevision`). */
export interface CompositionRevision {
    id: number;
    contentRevision: string;
    author: CompositionAuthor;
    turnId?: string;
    createdAt: string;
    content?: Record<string, unknown>;
}

/** A validation problem in a configuration item (mirrors `ConfigProblem`). */
export interface ConfigProblem {
    severity: "error" | "warning";
    path?: string;
    code: string;
    message: string;
}

/** Validation problems for one item (mirrors `CompositionItemProblems`). */
export interface CompositionItemProblems {
    itemId: number;
    type: CompositionItemType;
    name: string;
    valid: boolean;
    problems: ConfigProblem[];
}

/** Validation result for a whole composition (mirrors `CompositionValidationResult`). */
export interface CompositionValidationResult {
    items: CompositionItemProblems[];
}

/** Validation result for a single item's content. */
export interface ItemValidationResult {
    valid: boolean;
    problems: ConfigProblem[];
}

/** Result of undoing an AI turn (mirrors `CompositionUndoResult`). */
export interface UndoResult {
    restored: string[];
    removed: string[];
    skipped: string[];
    workflowBefore?: Record<string, unknown>;
    workflowRestorable: boolean;
}

/** Result of publishing composition items (mirrors `CompositionPublishResult`). */
export interface PublishResult {
    created: number;
    updated: number;
    items: CompositionItem[];
}

/** How the UI resolved a change set. */
export type ChangeSetOutcome = "applied" | "accepted" | "rejected" | "stale";

/** Payload of a `composition-item-changed` SSE event. */
export interface CompositionItemChangedEvent {
    compositionId: number;
    itemId: number;
    contentRevision: string;
    author: CompositionAuthor;
    turnId: string | null;
}

/** Payload of a `composition-turn` SSE event. */
export interface CompositionTurnEvent {
    compositionId: number;
    turnId: string | null;
    state: "idle" | "ai-working";
}

/** Payload of a `composition-changeset` SSE event. */
export interface CompositionChangeSetEvent {
    compositionId: number;
    turnId: string | null;
    changeSet: ChangeSet;
}

/** Error thrown for a non-2xx API response; keeps the HTTP status and parsed body. */
export class ApiError extends Error {
    /** HTTP status code. */
    readonly status: number;
    /** Parsed JSON body, or undefined if the body was not JSON. */
    readonly body: unknown;

    /**
     * @param message human-readable message
     * @param status HTTP status code
     * @param body parsed response body
     */
    constructor(message: string, status: number, body: unknown) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.body = body;
    }
}

// ── Helpers ───────────────────────────────────────────────────────

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const init: RequestInit = { method };
    if (body !== undefined) {
        init.headers = { "Content-Type": "application/json" };
        init.body = JSON.stringify(body);
    }
    const response = await fetch(`${API}${path}`, init);
    if (!response.ok) {
        const message = await extractErrorMessage(response, `${method} ${path} failed`);
        let parsed: unknown;
        try {
            parsed = await response.json();
        } catch {
            parsed = undefined;
        }
        throw new ApiError(message, response.status, parsed);
    }
    if (response.status === 204) {
        return undefined as T;
    }
    return (await response.json()) as T;
}

const c = (id: number) => `/compositions/${id}`;
const it = (id: number, itemId: number) => `${c(id)}/items/${itemId}`;

// ── Compositions ──────────────────────────────────────────────────

/** Lists all compositions. */
export function listCompositions(): Promise<Composition[]> {
    return request("GET", "/compositions");
}

/** Creates a composition, optionally seeded from an existing workflow. */
export function createComposition(data: { name: string; goal?: string; seedWorkflowId?: number }): Promise<Composition> {
    return request("POST", "/compositions", data);
}

/** Fetches one composition. */
export function fetchComposition(id: number): Promise<Composition> {
    return request("GET", c(id));
}

/** Updates a composition's name or apply mode. */
export function updateComposition(id: number, data: { name?: string; applyMode?: "live" | "review" }): Promise<Composition> {
    return request("PATCH", c(id), data);
}

/** Deletes a composition. */
export function deleteComposition(id: number): Promise<void> {
    return request("DELETE", c(id));
}

/** Starts or restores the composition's agent session. */
export function ensureCompositionSession(id: number): Promise<Composition> {
    return request("POST", `${c(id)}/session`);
}

// ── Items ─────────────────────────────────────────────────────────

/** Lists a composition's items. */
export function listItems(id: number): Promise<CompositionItem[]> {
    return request("GET", `${c(id)}/items`);
}

/** Adds a blank item or copies an existing item as a draft. */
export function addItem(id: number, data: {
    type: CompositionItemType;
    name?: string;
    existingItemId?: number;
    executionMode?: ExecutionMode;
}): Promise<CompositionItem> {
    return request("POST", `${c(id)}/items`, data);
}

/** Execution mode of an action type. */
export type ExecutionMode = "agent" | "script" | "node";

/** Fetches one item with content. */
export function fetchItem(id: number, itemId: number): Promise<CompositionItem> {
    return request("GET", it(id, itemId));
}

/** Saves item content against a base revision; throws ApiError 409 when stale. */
export function saveItem(
    id: number,
    itemId: number,
    data: { content: Record<string, unknown>; baseRevision: string; turnId?: string },
): Promise<CompositionItem> {
    return request("PUT", it(id, itemId), data);
}

/** Deletes an item. */
export function deleteItem(id: number, itemId: number): Promise<void> {
    return request("DELETE", it(id, itemId));
}

/** Lists an item's revisions. */
export function listRevisions(id: number, itemId: number): Promise<CompositionRevision[]> {
    return request("GET", `${it(id, itemId)}/revisions`);
}

/** Restores an item revision. */
export function restoreRevision(id: number, itemId: number, revisionId: number): Promise<CompositionItem> {
    return request("POST", `${it(id, itemId)}/revisions/${revisionId}/restore`);
}

/** Validates unsaved item content. */
export function validateItemContent(id: number, itemId: number, content: Record<string, unknown>): Promise<ItemValidationResult> {
    return request("POST", `${it(id, itemId)}/validate`, { content });
}

// ── Composition-wide actions ──────────────────────────────────────

/** Validates every item in a composition. */
export function validateComposition(id: number): Promise<CompositionValidationResult> {
    return request("POST", `${c(id)}/validate`);
}

/** Publishes the given items. */
export function publishComposition(id: number, itemIds: number[], force?: boolean): Promise<PublishResult> {
    const body: { itemIds: number[]; force?: boolean } = { itemIds };
    if (force !== undefined) {
        body.force = force;
    }
    return request("POST", `${c(id)}/publish`, body);
}

/** Sends a user message to the agent, starting a turn. */
export function sendCompositionMessage(id: number, data: { message: string; nodeIds?: string[]; edgeIds?: string[] }): Promise<Composition> {
    return request("POST", `${c(id)}/messages`, data);
}

/** Interrupts the running AI turn. */
export function interruptComposition(id: number): Promise<void> {
    return request("POST", `${c(id)}/interrupt`);
}

/** Reports how the UI resolved a change set. */
export function resolveChangeSet(id: number, data: { turnId: string; outcome: ChangeSetOutcome; fromPending: boolean }): Promise<void> {
    return request("POST", `${c(id)}/changeset-resolution`, data);
}

/** Undoes an AI turn. */
export function undoTurn(id: number, turnId: string): Promise<UndoResult> {
    return request("POST", `${c(id)}/turns/${encodeURIComponent(turnId)}/undo`);
}

// ── SSE ───────────────────────────────────────────────────────────

/** Handlers for composition SSE events. */
export interface CompositionEventHandlers {
    itemChanged?: (event: CompositionItemChangedEvent) => void;
    turn?: (event: CompositionTurnEvent) => void;
    changeSet?: (event: CompositionChangeSetEvent) => void;
}

/**
 * Subscribes to SSE events for one composition.
 *
 * @param compositionId the composition to listen to
 * @param handlers per-event-type callbacks
 * @returns an unsubscribe function
 */
export function subscribeComposition(compositionId: number, handlers: CompositionEventHandlers): () => void {
    return sseClient.subscribe((event) => {
        if (event.data?.compositionId !== compositionId) {
            return;
        }
        switch (event.type) {
            case "composition-item-changed":
                handlers.itemChanged?.(event.data as unknown as CompositionItemChangedEvent);
                break;
            case "composition-turn":
                handlers.turn?.(event.data as unknown as CompositionTurnEvent);
                break;
            case "composition-changeset":
                handlers.changeSet?.(event.data as unknown as CompositionChangeSetEvent);
                break;
        }
    });
}
