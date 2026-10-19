import { useMemo } from "react";
import type { ActionTypeDescriptor, EditorSpi } from "@apitomy/flow-ui";
import { fetchActionTypes } from "../config/api";
import { fetchSdkFunctions } from "../config/sdkTools";
import { actionTypeAppearance } from "../config/actionTypeAppearance";

/**
 * Loads the action types offered by the workflow editor's action-type picker: the configured action
 * types plus the SDK functions that can be called directly ({@code sdk:} prefix). SDK calls and AI agent
 * action types carry their own icon and color for Action nodes.
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
        ...actionTypeAppearance(at.name, at.executionMode),
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
            ...actionTypeAppearance(`sdk:${fn.name}`, undefined),
        }));
    return [...configured, ...sdkActions];
}

/**
 * Looks up the id of a configured action type by its name.
 *
 * @param name the action type name
 * @returns the id, or undefined when no action type has that name
 */
export async function findActionTypeIdByName(name: string): Promise<number | undefined> {
    const results = await fetchActionTypes(1, 1000);
    return results.items.find(at => at.name === name)?.id;
}

/** Options for {@link useWorkflowEditorSpi}. */
export interface WorkflowEditorSpiOptions {
    /** Called when the editor asks to open an action type. */
    openActionType?: (request: { value: string; resolved: boolean }) => void;
}

/**
 * A memoized {@link EditorSpi} for Axiom's workflow editors.
 *
 * @param options optional callbacks exposed through the SPI
 * @returns the SPI
 */
export function useWorkflowEditorSpi(options?: WorkflowEditorSpiOptions): EditorSpi {
    const openActionType = options?.openActionType;
    return useMemo(() => ({
        actionTypes: loadEditorActionTypes,
        ...(openActionType ? { openActionType } : {}),
    }), [openActionType]);
}
