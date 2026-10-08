import { useMemo } from "react";
import type { ActionTypeDescriptor, EditorSpi } from "@apitomy/flow-ui";
import { fetchActionTypes } from "../config/api";
import { fetchSdkFunctions } from "../config/sdkTools";

/**
 * Loads the action types offered by the workflow editor's action-type picker: the configured action
 * types plus the SDK functions that can be called directly ({@code sdk:} prefix).
 *
 * @returns the descriptors
 */
export async function loadEditorActionTypes(): Promise<ActionTypeDescriptor[]> {
    const [results, sdkFunctions] = await Promise.all([
        fetchActionTypes(1, 1000),
        fetchSdkFunctions(),
    ]);
    const configured = results.items.map((at): ActionTypeDescriptor => ({
        value: at.name,
        label: at.name,
        description: at.description,
        inputs: at.inputs,
        outputs: at.outputs,
    }));
    const sdkActions = sdkFunctions
        .filter(fn => fn.sdkCallSupported)
        .map((fn): ActionTypeDescriptor => ({
            value: `sdk:${fn.name}`,
            label: `SDK: ${fn.name.replace(/^axiom_/, "").replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase())}`,
            description: fn.description,
            inputs: fn.parameters?.map(p => ({
                name: p.name,
                type: (p.type || "string") as "string" | "number" | "boolean" | "object",
                required: p.required,
                description: p.description,
            })),
        }));
    return [...configured, ...sdkActions];
}

/**
 * A memoized {@link EditorSpi} for Axiom's workflow editors.
 *
 * @returns the SPI
 */
export function useWorkflowEditorSpi(): EditorSpi {
    return useMemo(() => ({ actionTypes: loadEditorActionTypes }), []);
}
