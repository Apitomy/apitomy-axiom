import { useEffect, useState } from "react";
import { type EventSchemaSummary, fetchEventSchema, fetchEventSchemas } from "../config/api";
import type { JsonSchema } from "../config/eventSchemaPaths";

/** Loading state of a cached request. */
export interface AsyncState<T> {
    data: T | undefined;
    loading: boolean;
    error: string | undefined;
}

let listPromise: Promise<EventSchemaSummary[]> | undefined;
const schemaPromises = new Map<string, Promise<JsonSchema>>();

/** Clears the session cache. Intended for tests. */
export function clearEventSchemaCache(): void {
    listPromise = undefined;
    schemaPromises.clear();
}

function cachedList(): Promise<EventSchemaSummary[]> {
    if (!listPromise) {
        listPromise = fetchEventSchemas().catch((e: unknown) => {
            listPromise = undefined;
            throw e;
        });
    }
    return listPromise;
}

function cachedSchema(eventType: string): Promise<JsonSchema> {
    let promise = schemaPromises.get(eventType);
    if (!promise) {
        promise = fetchEventSchema(eventType).catch((e: unknown) => {
            schemaPromises.delete(eventType);
            throw e;
        });
        schemaPromises.set(eventType, promise);
    }
    return promise;
}

function useCached<T>(key: string | undefined, load: (key: string) => Promise<T>): AsyncState<T> {
    const [state, setState] = useState<AsyncState<T>>(
        { data: undefined, loading: key !== undefined, error: undefined });
    useEffect(() => {
        if (key === undefined) {
            setState({ data: undefined, loading: false, error: undefined });
            return;
        }
        let cancelled = false;
        setState((prev) => ({ data: prev.data, loading: true, error: undefined }));
        load(key)
            .then((data) => {
                if (!cancelled) setState({ data, loading: false, error: undefined });
            })
            .catch((e: unknown) => {
                if (!cancelled) {
                    setState({ data: undefined, loading: false, error: e instanceof Error ? e.message : String(e) });
                }
            });
        return () => {
            cancelled = true;
        };
    }, [key]);
    return state;
}

/**
 * Loads the list of event types with schemas, cached for the browser session.
 *
 * @returns the loading state of the list
 */
export function useEventSchemas(): AsyncState<EventSchemaSummary[]> {
    return useCached("list", () => cachedList());
}

/**
 * Loads the assembled schema for one event type, cached per type for the browser session.
 *
 * @param eventType the event type, or undefined to load nothing
 * @returns the loading state of the schema
 */
export function useEventSchema(eventType: string | undefined): AsyncState<JsonSchema> {
    return useCached(eventType, cachedSchema);
}
