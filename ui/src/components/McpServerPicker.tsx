import { useEffect, useState } from "react";
import { Checkbox, HelperText, HelperTextItem, Label } from "@patternfly/react-core";
import { type McpServer, fetchMcpServers } from "../config/api";

/** A built-in Axiom MCP server, selected by its {@code @} alias. */
export interface BuiltInMcpServer {
    name: string;
    description: string;
}

/** Built-in Axiom MCP servers that session templates can select. */
export const BUILT_IN_MCP_SERVERS: BuiltInMcpServer[] = [
    { name: "@axiom-assistant", description: "Axiom assistant tools for the session's subject (mcp__axiom__*)" },
    { name: "@axiom-workbench", description: "Workbench composition tools (mcp__workbench__*)" },
    { name: "@axiom-tools", description: "Your custom script tools (mcp__axiom-tools__*)" },
    { name: "@axiom-sdk", description: "Axiom SDK tools (mcp__axiom-sdk__*)" },
];

/** Props of {@link McpServerPicker}. */
export interface McpServerPickerProps {
    /** Built-in servers to offer before the configured ones. */
    builtIns?: BuiltInMcpServer[];
    /** Names of the selected servers, in order. */
    selected: string[];
    /** Called with the new selection. */
    onChange: (selected: string[]) => void;
    /** Disables all checkboxes. */
    readOnly?: boolean;
}

/**
 * Checkbox list of configured MCP servers. Selected names that no longer exist are still listed (and
 * flagged) so they can be unselected; disabled servers are flagged because they will not be loaded.
 */
export function McpServerPicker({ selected, onChange, readOnly = false, builtIns = [] }: McpServerPickerProps) {
    const [servers, setServers] = useState<McpServer[] | null>(null);

    useEffect(() => {
        fetchMcpServers().then(setServers).catch(() => setServers([]));
    }, []);

    if (servers === null) {
        return <div className="axiom-text-subtle">Loading MCP servers...</div>;
    }

    const known = new Set([...servers.map((s) => s.name), ...builtIns.map((b) => b.name)]);
    const missing = selected.filter((name) => !known.has(name));

    const toggle = (name: string) => {
        onChange(selected.includes(name) ? selected.filter((n) => n !== name) : [...selected, name]);
    };

    if (servers.length === 0 && missing.length === 0 && builtIns.length === 0) {
        return <div className="axiom-text-subtle" style={{ fontStyle: "italic" }}>No MCP servers configured</div>;
    }

    return (
        <div>
            {builtIns.map((b) => (
                <Checkbox key={b.name} id={`mcp-server-${b.name}`} isDisabled={readOnly}
                    isChecked={selected.includes(b.name)} onChange={() => toggle(b.name)}
                    label={<>
                        {b.name}
                        <span className="axiom-text-subtle"> — {b.description}</span>
                        <Label isCompact color="blue" style={{ marginLeft: "8px" }}>Built-in</Label>
                    </>} />
            ))}
            {servers.map((server) => (
                <Checkbox key={server.name} id={`mcp-server-${server.name}`} isDisabled={readOnly}
                    isChecked={selected.includes(server.name)} onChange={() => toggle(server.name)}
                    label={<>
                        {server.name}
                        {server.description && <span className="axiom-text-subtle"> — {server.description}</span>}
                        {!server.enabled && <Label isCompact color="grey" style={{ marginLeft: "8px" }}>Disabled</Label>}
                    </>} />
            ))}
            {missing.map((name) => (
                <Checkbox key={name} id={`mcp-server-missing-${name}`} isDisabled={readOnly} isChecked
                    onChange={() => toggle(name)}
                    label={<>
                        {name}
                        <Label isCompact color="red" style={{ marginLeft: "8px" }}>Not found</Label>
                    </>} />
            ))}
            {missing.length > 0 && (
                <HelperText><HelperTextItem variant="error">
                    Servers marked "Not found" no longer exist and will not be loaded. Unselect them.
                </HelperTextItem></HelperText>
            )}
        </div>
    );
}
