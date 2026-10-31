import type { McpCatalogEntry, McpCatalogInput, NewMcpServer } from "../config/api";

/** Where an input's value goes. */
export type CatalogInputKind = "arg" | "env" | "header";

/** How the user supplies a value: typed in, an existing secret, or a new secret created on add. */
export interface CatalogValue {
    mode: "plain" | "secret" | "newSecret";
    /** Plain value, or the value of a new secret. */
    value: string;
    /** Existing or new secret name. */
    secretName?: string;
}

/** An input of a catalog entry, keyed for form state. */
export interface CatalogFormInput {
    key: string;
    kind: CatalogInputKind;
    input: McpCatalogInput;
    initial: CatalogValue;
}

function secretSafe(name: string): string {
    return name.toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

/** Suggests a secret name: env variables keep their name, headers become {@code <SERVER>_TOKEN}. */
export function defaultSecretName(entry: McpCatalogEntry, kind: CatalogInputKind, name: string): string {
    return kind === "env" ? secretSafe(name) : `${secretSafe(entry.name)}_TOKEN`;
}

/** Lists the inputs of an entry in display order: arguments, environment variables, headers. */
export function catalogInputs(entry: McpCatalogEntry): CatalogFormInput[] {
    const groups: [CatalogInputKind, McpCatalogInput[] | undefined][] = [
        ["arg", entry.arguments], ["env", entry.env], ["header", entry.headers],
    ];
    return groups.flatMap(([kind, inputs]) => (inputs ?? []).map((input) => ({
        key: `${kind}:${input.name}`,
        kind,
        input,
        initial: input.secret
            ? { mode: "newSecret" as const, value: "", secretName: defaultSecretName(entry, kind, input.name) }
            : { mode: "plain" as const, value: input.defaultValue ?? "" },
    })));
}

/** Resolves a form value to the stored string, or undefined when empty. */
function resolve(input: McpCatalogInput, value: CatalogValue | undefined, forTest: boolean): string | undefined {
    if (!value) return undefined;
    let raw: string;
    if (value.mode === "plain" || (value.mode === "newSecret" && forTest)) {
        raw = value.value;
    } else {
        if (!value.secretName) return undefined;
        if (value.mode === "newSecret" && !value.value) return undefined;
        raw = `\${secret:${value.secretName}}`;
    }
    if (!raw) return undefined;
    return input.template ? input.template.replace("{value}", raw) : raw;
}

/**
 * Builds the MCP server to create from a catalog entry and the user's values.
 *
 * @param options.forTest use the typed values of new secrets directly (they are not created when testing)
 */
export function buildCatalogServer(entry: McpCatalogEntry, name: string, values: Record<string, CatalogValue>,
    options: { forTest?: boolean } = {}): NewMcpServer {
    const forTest = options.forTest ?? false;
    const server: NewMcpServer = { name, description: entry.description, transport: entry.transport };
    const collect = (kind: CatalogInputKind, inputs?: McpCatalogInput[]) => {
        const out: Record<string, string> = {};
        (inputs ?? []).forEach((input) => {
            const v = resolve(input, values[`${kind}:${input.name}`], forTest);
            if (v !== undefined) out[input.name] = v;
        });
        return out;
    };
    if (entry.transport === "stdio") {
        const argValues = collect("arg", entry.arguments);
        server.serverCommand = entry.command;
        server.serverArgs = (entry.args ?? []).map((a) => a.replace(/\{arg:([^}]+)\}/g, (_m, n: string) => argValues[n] ?? ""));
        const env = collect("env", entry.env);
        if (Object.keys(env).length > 0) server.serverEnv = env;
    } else {
        server.serverUrl = entry.url;
        const headers = collect("header", entry.headers);
        if (Object.keys(headers).length > 0) server.headers = headers;
    }
    return server;
}

/** Lists the new secrets (with values) that must be created before adding the server. */
export function newSecretsToCreate(values: Record<string, CatalogValue>): { name: string; value: string }[] {
    return Object.values(values)
        .filter((v) => v.mode === "newSecret" && v.secretName && v.value)
        .map((v) => ({ name: v.secretName as string, value: v.value }));
}

/** Whether every required input has a value. */
export function missingRequired(inputs: CatalogFormInput[], values: Record<string, CatalogValue>): string[] {
    return inputs.filter((i) => i.input.required).filter((i) => {
        const v = values[i.key];
        if (!v) return true;
        if (v.mode === "plain") return !v.value.trim();
        if (v.mode === "secret") return !v.secretName;
        return !v.secretName || !v.value;
    }).map((i) => i.input.name);
}
