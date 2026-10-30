import { useEffect, useState } from "react";
import { Checkbox, HelperText, HelperTextItem, Label } from "@patternfly/react-core";
import { type McpServer, fetchMcpServers } from "../config/api";

/** Props of {@link McpServerPicker}. */
export interface McpServerPickerProps {
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
export function McpServerPicker({ selected, onChange, readOnly = false }: McpServerPickerProps) {
    const [servers, setServers] = useState<McpServer[] | null>(null);

    useEffect(() => {
        fetchMcpServers().then(setServers).catch(() => setServers([]));
    }, []);

    if (servers === null) {
        return <div className="axiom-text-subtle">Loading MCP servers...</div>;
    }

    const known = new Set(servers.map((s) => s.name));
    const missing = selected.filter((name) => !known.has(name));

    const toggle = (name: string) => {
        onChange(selected.includes(name) ? selected.filter((n) => n !== name) : [...selected, name]);
    };

    if (servers.length === 0 && missing.length === 0) {
        return <div className="axiom-text-subtle" style={{ fontStyle: "italic" }}>No MCP servers configured</div>;
    }

    return (
        <div>
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
