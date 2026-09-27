import { getApiBaseUrl } from "./api";

const MCP_PREFIX = "mcp__axiom-sdk__";

export interface SdkToolEntry {
    value: string;
    label: string;
    description: string;
}

export interface SdkFunctionDescriptor {
    name: string;
    description: string;
    parameters: SdkFunctionParam[];
    sdkCallSupported: boolean;
}

export interface SdkFunctionParam {
    name: string;
    type: string;
    description: string;
    required: boolean;
}

let cachedFunctions: SdkFunctionDescriptor[] | null = null;
let cachedTools: SdkToolEntry[] | null = null;
let cachedToolValues: string[] | null = null;

async function loadRegistry(): Promise<SdkFunctionDescriptor[]> {
    if (cachedFunctions) return cachedFunctions;
    const API = `${getApiBaseUrl()}/api/v1`;
    const response = await fetch(`${API}/sdk/functions`);
    if (!response.ok) throw new Error(`Failed to load SDK functions: ${response.status}`);
    cachedFunctions = await response.json();
    return cachedFunctions!;
}

export async function fetchSdkFunctions(): Promise<SdkFunctionDescriptor[]> {
    return loadRegistry();
}

export async function fetchSdkTools(): Promise<SdkToolEntry[]> {
    if (cachedTools) return cachedTools;
    const fns = await loadRegistry();
    cachedTools = fns.map(fn => ({
        value: MCP_PREFIX + fn.name,
        label: fn.name,
        description: fn.description,
    }));
    return cachedTools;
}

export async function fetchSdkToolValues(): Promise<string[]> {
    if (cachedToolValues) return cachedToolValues;
    const tools = await fetchSdkTools();
    cachedToolValues = tools.map(t => t.value);
    return cachedToolValues;
}
