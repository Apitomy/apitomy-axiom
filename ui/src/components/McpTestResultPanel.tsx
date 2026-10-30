import { Alert, ExpandableSection } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import type { McpServerTestResult } from "../config/api";

/** Props of {@link McpTestResultPanel}. */
export interface McpTestResultPanelProps {
    /** The test result. */
    result: McpServerTestResult;
    /** Server name, used to show allowed-tool names ({@code mcp__<server>__<tool>}). */
    serverName: string;
}

/** Shows the outcome of an MCP server connection test: the tools found, or why it failed. */
export function McpTestResultPanel({ result, serverName }: McpTestResultPanelProps) {
    const seconds = (result.durationMs / 1000).toFixed(1);
    if (!result.success) {
        return (
            <Alert variant="danger" isInline title={result.error || "Connection test failed"}>
                {result.log && (
                    <ExpandableSection toggleText="Server output" isIndented>
                        <pre style={{ whiteSpace: "pre-wrap", maxHeight: "240px", overflow: "auto" }}>{result.log}</pre>
                    </ExpandableSection>
                )}
            </Alert>
        );
    }
    const server = [result.serverName, result.serverVersion].filter(Boolean).join(" ");
    return (
        <Alert variant="success" isInline
            title={`Connected to ${server || serverName} in ${seconds}s — ${result.tools.length} tool(s)`}>
            {result.tools.length > 0 && (
                <Table aria-label="Tools" variant="compact">
                    <Thead><Tr><Th>Allowed tool name</Th><Th>Description</Th></Tr></Thead>
                    <Tbody>
                        {result.tools.map((t) => (
                            <Tr key={t.name}>
                                <Td><code>{`mcp__${serverName}__${t.name}`}</code></Td>
                                <Td>{t.description || "—"}</Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </Alert>
    );
}
