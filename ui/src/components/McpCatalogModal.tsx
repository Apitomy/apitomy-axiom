import { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    Card,
    CardBody,
    CardHeader,
    CardTitle,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    Radio,
    SearchInput,
    Tab,
    TabTitleText,
    Tabs,
    TextInput,
} from "@patternfly/react-core";
import ArrowLeftIcon from "@patternfly/react-icons/dist/esm/icons/arrow-left-icon";
import {
    type McpCatalogEntry,
    type McpRuntimeStatus,
    type McpServer,
    type McpServerTestResult,
    createMcpServer,
    createSecret,
    fetchMcpRuntimeStatus,
    fetchSecrets,
    searchMcpCatalog,
    testNewMcpServer,
} from "../config/api";
import { McpTestResultPanel } from "./McpTestResultPanel";
import {
    type CatalogFormInput,
    type CatalogValue,
    buildCatalogServer,
    catalogInputs,
    missingRequired,
    newSecretsToCreate,
} from "../utils/mcpCatalog";

const RUNTIME_COMMANDS: Record<string, { key: keyof McpRuntimeStatus; command: string; label: string }> = {
    node: { key: "node", command: "npx", label: "Node.js" },
    uv: { key: "uv", command: "uvx", label: "uv" },
    docker: { key: "docker", command: "docker", label: "Docker" },
};

/** Label and availability warning for an entry's runtime. */
function RuntimeLabel({ entry, runtimes }: { entry: McpCatalogEntry; runtimes: McpRuntimeStatus | null }) {
    if (!entry.runtime || entry.runtime === "remote") {
        return <Label isCompact color="blue">Remote</Label>;
    }
    const info = RUNTIME_COMMANDS[entry.runtime];
    const missing = runtimes !== null && runtimes[info.key] === false;
    return (
        <>
            <Label isCompact color={missing ? "orange" : "green"}>{info.label}</Label>
            {missing && (
                <span className="axiom-text-subtle" style={{ marginLeft: "8px", fontSize: "12px" }}>
                    {info.command} is not installed on the Axiom host
                </span>
            )}
        </>
    );
}

/** One input row of the configure step. */
function InputField({ field, value, secrets, onChange }: {
    field: CatalogFormInput; value: CatalogValue; secrets: string[]; onChange: (v: CatalogValue) => void;
}) {
    const { input } = field;
    const group = `catalog-mode-${field.key}`;
    const kindLabel = field.kind === "env" ? "Environment variable" : field.kind === "header" ? "Header" : "Argument";
    return (
        <FormGroup label={input.name} isRequired={input.required} fieldId={group}>
            {input.secret && (
                <Flex style={{ gap: "16px", marginBottom: "4px" }}>
                    <Radio id={`${group}-new`} name={group} label="New secret" isChecked={value.mode === "newSecret"}
                        onChange={() => onChange({ ...value, mode: "newSecret" })} />
                    <Radio id={`${group}-existing`} name={group} label="Existing secret" isChecked={value.mode === "secret"}
                        onChange={() => onChange({ ...value, mode: "secret", secretName: secrets[0] ?? "" })} />
                    <Radio id={`${group}-plain`} name={group} label="Plain value" isChecked={value.mode === "plain"}
                        onChange={() => onChange({ ...value, mode: "plain" })} />
                </Flex>
            )}
            {value.mode === "secret" ? (
                <FormSelect aria-label={`Secret for ${input.name}`} value={value.secretName ?? ""}
                    onChange={(_e, v) => onChange({ ...value, secretName: v })}>
                    <FormSelectOption value="" label={secrets.length ? "Choose a secret…" : "No secrets defined"} />
                    {secrets.map((s) => <FormSelectOption key={s} value={s} label={s} />)}
                </FormSelect>
            ) : (
                <Flex style={{ gap: "8px" }} flexWrap={{ default: "nowrap" }}>
                    {value.mode === "newSecret" && (
                        <FlexItem style={{ flex: 1 }}>
                            <TextInput aria-label={`Secret name for ${input.name}`} value={value.secretName ?? ""}
                                placeholder="Secret name" onChange={(_e, v) => onChange({ ...value, secretName: v })} />
                        </FlexItem>
                    )}
                    <FlexItem style={{ flex: 2 }}>
                        <TextInput aria-label={`Value for ${input.name}`} value={value.value}
                            type={input.secret ? "password" : "text"}
                            placeholder={input.defaultValue ?? ""}
                            onChange={(_e, v) => onChange({ ...value, value: v })} />
                    </FlexItem>
                </Flex>
            )}
            <FormHelperText><HelperText><HelperTextItem>
                {kindLabel}{input.description ? ` — ${input.description}` : ""}
                {input.template ? ` (sent as ${input.template.replace("{value}", "<value>")})` : ""}
                {value.mode === "newSecret" ? ". The value is saved as an Axiom secret when you add the server." : ""}
            </HelperTextItem></HelperText></FormHelperText>
        </FormGroup>
    );
}

/** Props of {@link McpCatalogModal}. */
export interface McpCatalogModalProps {
    isOpen: boolean;
    onClose: () => void;
    /** Called with the created server. */
    onCreated: (server: McpServer) => void;
}

/**
 * Lets users browse the curated MCP catalog or search the MCP Registry, fill in the values a server needs
 * (storing credentials as secrets), test it and add it.
 */
export function McpCatalogModal({ isOpen, onClose, onCreated }: McpCatalogModalProps) {
    const [source, setSource] = useState<"curated" | "registry">("curated");
    const [search, setSearch] = useState("");
    const [entries, setEntries] = useState<McpCatalogEntry[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [runtimes, setRuntimes] = useState<McpRuntimeStatus | null>(null);
    const [selected, setSelected] = useState<McpCatalogEntry | null>(null);
    const [name, setName] = useState("");
    const [values, setValues] = useState<Record<string, CatalogValue>>({});
    const [secrets, setSecrets] = useState<string[]>([]);
    const [busy, setBusy] = useState(false);
    const [testResult, setTestResult] = useState<McpServerTestResult | null>(null);

    const load = (src: "curated" | "registry", term: string) => {
        setLoading(true);
        setError(null);
        searchMcpCatalog(src, term)
            .then(setEntries)
            .catch((e: Error) => { setEntries([]); setError(e.message); })
            .finally(() => setLoading(false));
    };

    useEffect(() => {
        if (!isOpen) return;
        setSource("curated");
        setSearch("");
        setSelected(null);
        setTestResult(null);
        load("curated", "");
        fetchMcpRuntimeStatus().then(setRuntimes).catch(() => setRuntimes(null));
        fetchSecrets().then((list) => setSecrets(list.map((s) => s.name).sort())).catch(() => setSecrets([]));
    }, [isOpen]);

    const inputs = useMemo(() => (selected ? catalogInputs(selected) : []), [selected]);

    const choose = (entry: McpCatalogEntry) => {
        setSelected(entry);
        setName(entry.name);
        setValues(Object.fromEntries(catalogInputs(entry).map((i) => [i.key, i.initial])));
        setTestResult(null);
        setError(null);
    };

    const switchSource = (src: "curated" | "registry") => {
        setSource(src);
        setEntries([]);
        if (src === "curated") {
            load("curated", search);
        }
    };

    const missing = missingRequired(inputs, values);

    const handleTest = () => {
        if (!selected) return;
        setBusy(true);
        setTestResult(null);
        setError(null);
        testNewMcpServer(buildCatalogServer(selected, name.trim(), values, { forTest: true }))
            .then(setTestResult)
            .catch((e: Error) => setError(e.message))
            .finally(() => setBusy(false));
    };

    const handleAdd = async () => {
        if (!selected) return;
        setBusy(true);
        setError(null);
        try {
            for (const secret of newSecretsToCreate(values)) {
                if (!secrets.includes(secret.name)) {
                    await createSecret({ name: secret.name, value: secret.value,
                        description: `MCP server ${name.trim()}` });
                }
            }
            const created = await createMcpServer(buildCatalogServer(selected, name.trim(), values));
            onCreated(created);
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Add from catalog">
            <ModalHeader title={selected ? `Add ${selected.title}` : "Add from catalog"} />
            <ModalBody>
                {error && <Alert variant="danger" isInline title={error} style={{ marginBottom: "16px" }} />}
                {!selected ? (
                    <>
                        <Tabs activeKey={source} onSelect={(_e, k) => switchSource(k as "curated" | "registry")}>
                            <Tab eventKey="curated" title={<TabTitleText>Popular</TabTitleText>} />
                            <Tab eventKey="registry" title={<TabTitleText>MCP Registry</TabTitleText>} />
                        </Tabs>
                        <SearchInput style={{ margin: "16px 0" }} aria-label="Search the catalog"
                            placeholder={source === "curated" ? "Filter popular servers" : "Search the official MCP Registry"}
                            value={search}
                            onChange={(_e, v) => { setSearch(v); if (source === "curated") load("curated", v); }}
                            onSearch={(_e, v) => load(source, v)}
                            onClear={() => { setSearch(""); load(source, ""); }} />
                        {source === "registry" && (
                            <p className="axiom-text-subtle" style={{ marginBottom: "12px" }}>
                                Servers in the MCP Registry are published by their authors and are not reviewed by
                                Axiom. Check a server before giving it credentials.
                            </p>
                        )}
                        {loading ? (
                            <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                        ) : entries.length === 0 ? (
                            <EmptyState><EmptyStateBody>
                                {source === "registry" && !search.trim() && !error
                                    ? "Type a search term and press Enter." : "No servers found."}
                            </EmptyStateBody></EmptyState>
                        ) : (
                            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: "12px" }}>
                                {entries.map((entry) => (
                                    <Card key={entry.id} isCompact>
                                        <CardHeader actions={{ actions: (
                                            <Button variant="secondary" size="sm" aria-label={`Select ${entry.title}`}
                                                onClick={() => choose(entry)}>Select</Button>
                                        ) }}>
                                            <CardTitle>{entry.title}</CardTitle>
                                        </CardHeader>
                                        <CardBody>
                                            <p style={{ marginBottom: "8px" }}>{entry.description}</p>
                                            <RuntimeLabel entry={entry} runtimes={runtimes} />
                                            {entry.source === "registry" && entry.title !== entry.id && (
                                                <div className="axiom-text-subtle" style={{ fontSize: "12px", marginTop: "4px" }}>{entry.id}</div>
                                            )}
                                        </CardBody>
                                    </Card>
                                ))}
                            </div>
                        )}
                    </>
                ) : (
                    <Form>
                        <p>{selected.description}</p>
                        {selected.homepage && (
                            <p><a href={selected.homepage} target="_blank" rel="noreferrer">{selected.homepage}</a></p>
                        )}
                        {selected.notes && <Alert variant="info" isInline isPlain title={selected.notes} />}
                        <div><RuntimeLabel entry={selected} runtimes={runtimes} /></div>
                        <FormGroup label="Server name" isRequired fieldId="catalog-name">
                            <TextInput id="catalog-name" value={name} onChange={(_e, v) => setName(v)} />
                            <FormHelperText><HelperText><HelperTextItem>
                                Tools will be named <code>{`mcp__${name.trim() || "<name>"}__<tool>`}</code>.
                            </HelperTextItem></HelperText></FormHelperText>
                        </FormGroup>
                        {inputs.map((field) => (
                            <InputField key={field.key} field={field} value={values[field.key]} secrets={secrets}
                                onChange={(v) => setValues((prev) => ({ ...prev, [field.key]: v }))} />
                        ))}
                        {testResult && <McpTestResultPanel result={testResult} serverName={name.trim()} />}
                    </Form>
                )}
            </ModalBody>
            <ModalFooter>
                {selected ? (
                    <>
                        <Button variant="primary" onClick={handleAdd} isLoading={busy}
                            isDisabled={busy || !name.trim() || missing.length > 0}>Add server</Button>
                        <Button variant="secondary" onClick={handleTest} isDisabled={busy || !name.trim() || missing.length > 0}>
                            Test
                        </Button>
                        <Button variant="link" icon={<ArrowLeftIcon />} onClick={() => setSelected(null)}>Back</Button>
                    </>
                ) : (
                    <Button variant="link" onClick={onClose}>Cancel</Button>
                )}
            </ModalFooter>
        </Modal>
    );
}
