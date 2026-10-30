import { useState, useEffect, useCallback, useMemo } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
    Alert,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Label,
    List,
    ListItem,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    SearchInput,
    Tab,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    Radio,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type McpServer,
    type McpServerInUseError,
    type McpServerTestResult,
    type McpTransport,
    type NewMcpServer,
    mcpServerUsageRoute,
    fetchMcpServers,
    createMcpServer,
    deleteMcpServer,
    testNewMcpServer,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { StringListEditor } from "../components/StringListEditor";
import { McpTestResultPanel } from "../components/McpTestResultPanel";
import { parseMcpServersJson } from "../utils/mcpServerJson";

const USAGE_TYPE_LABELS: Record<string, string> = {
    actionType: "Action type",
    sessionTemplate: "Session template",
    toolset: "Toolset",
};

const TRANSPORT_LABELS: Record<McpTransport, { text: string; color: "green" | "blue" | "purple" }> = {
    stdio: { text: "stdio", color: "green" },
    http: { text: "HTTP", color: "blue" },
    sse: { text: "SSE", color: "purple" },
};

const PASTE_PLACEHOLDER = `{
  "mcpServers": {
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "\${secret:GITHUB_TOKEN}" }
    }
  }
}`;

/** Result of importing pasted servers. */
interface ImportOutcome {
    created: McpServer[];
    failed: { name: string; error: string }[];
}

/** Create dialog: a form for one server (optionally tested first) or a pasted JSON snippet. */
function CreateMcpServerModal({ isOpen, onClose, onCreated }: {
    isOpen: boolean;
    onClose: () => void;
    onCreated: (created: McpServer[]) => void;
}) {
    const [tab, setTab] = useState(0);
    const [name, setName] = useState("");
    const [transport, setTransport] = useState<McpTransport>("stdio");
    const [target, setTarget] = useState("");
    const [args, setArgs] = useState<string[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [testResult, setTestResult] = useState<McpServerTestResult | null>(null);
    const [testing, setTesting] = useState(false);
    const [pasted, setPasted] = useState("");
    const [outcome, setOutcome] = useState<ImportOutcome | null>(null);
    const [importing, setImporting] = useState(false);

    useEffect(() => {
        if (isOpen) {
            setTab(0);
            setName("");
            setTransport("stdio");
            setTarget("");
            setArgs([]);
            setError(null);
            setTestResult(null);
            setPasted("");
            setOutcome(null);
        }
    }, [isOpen]);

    const draft = (): NewMcpServer => {
        const value = target.trim();
        if (transport === "stdio") {
            return args.length > 0
                ? { name: name.trim(), transport, serverCommand: value, serverArgs: args }
                : { name: name.trim(), transport, serverCommand: value };
        }
        return { name: name.trim(), transport, serverUrl: value };
    };

    const canSubmitForm = name.trim() !== "" && target.trim() !== "";
    const parsed = useMemo(() => (pasted.trim() ? parseMcpServersJson(pasted) : null), [pasted]);

    const handleCreate = () => {
        setError(null);
        createMcpServer(draft())
            .then((created) => onCreated([created]))
            .catch((e: Error) => setError(e.message));
    };

    const handleTest = () => {
        setTesting(true);
        setError(null);
        setTestResult(null);
        testNewMcpServer(draft())
            .then(setTestResult)
            .catch((e: Error) => setError(e.message))
            .finally(() => setTesting(false));
    };

    const handleImport = async () => {
        if (!parsed) return;
        setImporting(true);
        const result: ImportOutcome = { created: [], failed: [] };
        for (const server of parsed.servers) {
            try {
                result.created.push(await createMcpServer(server));
            } catch (e) {
                result.failed.push({ name: server.name, error: (e as Error).message });
            }
        }
        setImporting(false);
        if (result.failed.length === 0) {
            onCreated(result.created);
        } else {
            setOutcome(result);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="medium">
            <ModalHeader title="Add MCP Server" />
            <ModalBody>
                <Tabs activeKey={tab} onSelect={(_e, k) => setTab(k as number)}>
                    <Tab eventKey={0} title={<TabTitleText>Form</TabTitleText>}>
                        <Form style={{ marginTop: "16px" }}>
                            {error && <Alert variant="danger" isInline title={error} />}
                            <FormGroup label="Name" isRequired fieldId="name">
                                <TextInput id="name" isRequired value={name} onChange={(_e, v) => setName(v)} />
                                <FormHelperText><HelperText><HelperTextItem>
                                    Letters, digits, <code>-</code> and <code>_</code>. Tools are named{" "}
                                    <code>{`mcp__${name.trim() || "<name>"}__<tool>`}</code>.
                                </HelperTextItem></HelperText></FormHelperText>
                            </FormGroup>
                            <FormGroup label="Transport" isRequired role="radiogroup" fieldId="transport" isInline>
                                <Radio id="transport-stdio" name="transport" label="stdio (local command)"
                                    isChecked={transport === "stdio"} onChange={() => setTransport("stdio")} />
                                <Radio id="transport-http" name="transport" label="HTTP (streamable)"
                                    isChecked={transport === "http"} onChange={() => setTransport("http")} />
                                <Radio id="transport-sse" name="transport" label="SSE (legacy)"
                                    isChecked={transport === "sse"} onChange={() => setTransport("sse")} />
                            </FormGroup>
                            {transport === "stdio" ? (
                                <>
                                    <FormGroup label="Command" isRequired fieldId="new-command">
                                        <TextInput id="new-command" isRequired value={target}
                                            onChange={(_e, v) => setTarget(v)} placeholder="e.g. npx, uvx, docker, node" />
                                    </FormGroup>
                                    <FormGroup label="Arguments" fieldId="new-args">
                                        <StringListEditor label="Arguments" items={args} onChange={setArgs}
                                            placeholder="e.g. -y" />
                                    </FormGroup>
                                </>
                            ) : (
                                <FormGroup label="URL" isRequired fieldId="new-url">
                                    <TextInput id="new-url" isRequired value={target}
                                        onChange={(_e, v) => setTarget(v)} placeholder="https://mcp.example.com/mcp" />
                                </FormGroup>
                            )}
                            <p className="axiom-text-subtle">
                                Environment variables, headers and secrets can be set after creating the server.
                            </p>
                            {testResult && <McpTestResultPanel result={testResult} serverName={name.trim()} />}
                        </Form>
                    </Tab>
                    <Tab eventKey={1} title={<TabTitleText>Paste JSON</TabTitleText>}>
                        <Form style={{ marginTop: "16px" }}>
                            <FormGroup label="MCP server configuration" fieldId="paste-json">
                                <TextArea id="paste-json" aria-label="MCP server configuration" value={pasted}
                                    onChange={(_e, v) => { setPasted(v); setOutcome(null); }}
                                    rows={12} style={{ fontFamily: "monospace" }} placeholder={PASTE_PLACEHOLDER} />
                                <FormHelperText><HelperText><HelperTextItem>
                                    Paste the <code>mcpServers</code> block from a server's README or from Claude Code,
                                    Cursor, Windsurf or Cline, or the <code>mcp</code> block from OpenCode.
                                </HelperTextItem></HelperText></FormHelperText>
                            </FormGroup>
                            {parsed && parsed.errors.map((e) => (
                                <Alert key={e} variant="danger" isInline isPlain title={e} />
                            ))}
                            {parsed && parsed.servers.length > 0 && (
                                <Alert variant="info" isInline
                                    title={`${parsed.servers.length} server(s): ${parsed.servers.map((s) => s.name).join(", ")}`} />
                            )}
                            {parsed && parsed.secretWarnings.length > 0 && (
                                <Alert variant="warning" isInline title="Plain values that look like credentials">
                                    {parsed.secretWarnings.join(", ")}. They will be stored as plain text and exported
                                    with configuration packs. Consider creating secrets and using{" "}
                                    <code>{"${secret:NAME}"}</code> instead.
                                </Alert>
                            )}
                            {outcome && (
                                <Alert variant="danger" isInline
                                    title={`Created ${outcome.created.length}, failed ${outcome.failed.length}`}>
                                    <List>
                                        {outcome.failed.map((f) => <ListItem key={f.name}>{f.name}: {f.error}</ListItem>)}
                                    </List>
                                </Alert>
                            )}
                        </Form>
                    </Tab>
                </Tabs>
            </ModalBody>
            <ModalFooter>
                {tab === 0 ? (
                    <>
                        <Button variant="primary" onClick={handleCreate} isDisabled={!canSubmitForm}>Create</Button>
                        <Button variant="secondary" onClick={handleTest} isDisabled={!canSubmitForm || testing}
                            isLoading={testing}>
                            {testing ? "Testing..." : "Test"}
                        </Button>
                    </>
                ) : (
                    <Button variant="primary" onClick={handleImport} isLoading={importing}
                        isDisabled={!parsed || parsed.servers.length === 0 || importing}>
                        {parsed && parsed.servers.length > 1 ? `Import ${parsed.servers.length} servers` : "Import"}
                    </Button>
                )}
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}

export function McpServersPage() {
    const navigate = useNavigate();
    const [servers, setServers] = useState<McpServer[]>([]);
    const [loading, setLoading] = useState(true);
    const [search, setSearch] = useState("");
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [deleteError, setDeleteError] = useState<McpServerInUseError | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchMcpServers().then(setServers).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreated = (created: McpServer[]) => {
        setIsCreateOpen(false);
        if (created.length === 1) {
            navigate(`/mcp-servers/${created[0].id}`);
        } else {
            load();
        }
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            setDeleteError(null);
            deleteMcpServer(deleteTarget).then(load).catch((e: McpServerInUseError) => setDeleteError(e));
            setDeleteTarget(null);
        }
    };

    const term = search.trim().toLowerCase();
    const visible = servers.filter((s) => !term || s.name.toLowerCase().includes(term)
        || (s.description || "").toLowerCase().includes(term));

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">MCP Servers</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={() => setIsCreateOpen(true)}>
                        Add MCP Server
                    </Button>
                </FlexItem>
            </Flex>
            <p className="axiom-text-subtle" style={{ marginTop: "8px" }}>
                External MCP servers give AI agents extra tools. Select them on action types and session templates,
                or allow individual tools as <code>{"mcp__<server>__<tool>"}</code>.
            </p>

            {deleteError && (
                <Alert variant="danger" isInline title={deleteError.message} style={{ marginTop: "16px" }}
                    actionClose={<Button variant="plain" aria-label="Dismiss" onClick={() => setDeleteError(null)}>×</Button>}>
                    {deleteError.usages && deleteError.usages.length > 0 && (
                        <ul>
                            {deleteError.usages.map((u) => (
                                <li key={`${u.type}-${u.name}-${u.field}`}>
                                    {USAGE_TYPE_LABELS[u.type]} <Link to={mcpServerUsageRoute(u)}>{u.name}</Link>
                                    {" "}({u.field})
                                </li>
                            ))}
                        </ul>
                    )}
                </Alert>
            )}

            {servers.length > 0 && (
                <SearchInput style={{ marginTop: "16px", maxWidth: "400px" }} placeholder="Search by name or description"
                    aria-label="Search MCP servers" value={search}
                    onChange={(_e, v) => setSearch(v)} onClear={() => setSearch("")} />
            )}

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : servers.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No MCP servers registered.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="MCP Servers" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Transport</Th>
                                <Th>Status</Th>
                                <Th>Tools</Th>
                                <Th>Used by</Th>
                                <Th screenReaderText="Actions" />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {visible.map((s) => {
                                const transport = TRANSPORT_LABELS[s.transport] ?? TRANSPORT_LABELS.stdio;
                                return (
                                    <Tr key={s.id} isClickable
                                        onRowClick={() => navigate(`/mcp-servers/${s.id}`)}>
                                        <Td>{s.name}</Td>
                                        <Td>{s.description || "—"}</Td>
                                        <Td><Label isCompact color={transport.color}>{transport.text}</Label></Td>
                                        <Td>
                                            {s.enabled
                                                ? <Label isCompact color="green">Enabled</Label>
                                                : <Label isCompact color="grey">Disabled</Label>}
                                            {s.warnings && s.warnings.length > 0 && (
                                                <Label isCompact color="orange" style={{ marginLeft: "4px" }}
                                                    title={s.warnings.join("\n")}>
                                                    {s.warnings.length} warning(s)
                                                </Label>
                                            )}
                                        </Td>
                                        <Td>{s.tools && s.tools.length > 0 ? s.tools.length : "—"}</Td>
                                        <Td>{s.usageCount ?? 0}</Td>
                                        <Td>
                                            <Button variant="plain" size="sm" style={{ padding: 0 }}
                                                aria-label={`Delete ${s.name}`}
                                                onClick={(e) => handleDelete(e, s.id)}>
                                                <TrashIcon />
                                            </Button>
                                        </Td>
                                    </Tr>
                                );
                            })}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete MCP Server"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this MCP server?
            </ConfirmDeleteModal>

            <CreateMcpServerModal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)}
                onCreated={handleCreated} />
        </PageSection>
    );
}
