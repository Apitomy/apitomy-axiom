import { useState, useEffect, useCallback } from "react";
import {
    Alert,
    Button,
    Checkbox,
    EmptyState,
    FileUpload,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    SimpleList,
    SimpleListItem,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import DownloadIcon from "@patternfly/react-icons/dist/esm/icons/download-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import UploadIcon from "@patternfly/react-icons/dist/esm/icons/upload-icon";
import {
    type ActionType,
    type ToolDefinition,
    type Toolset,
    type McpServer,
    type ReportDefinition,
    type ScheduledJob,
    type SessionTemplate,
    type PackExportRequest,
    type ImportResult,
    fetchActionTypes,
    fetchTools,
    fetchToolsets,
    fetchMcpServers,
    fetchReportDefinitions,
    fetchScheduledJobs,
    fetchAssistantTemplates,
    exportPack,
    importPack,
} from "../config/api";

export function ConfigurationPacksPage() {
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [tools, setTools] = useState<ToolDefinition[]>([]);
    const [toolsets, setToolsets] = useState<Toolset[]>([]);
    const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
    const [reportDefs, setReportDefs] = useState<ReportDefinition[]>([]);
    const [scheduledJobs, setScheduledJobs] = useState<ScheduledJob[]>([]);
    const [sessionTemplates, setSessionTemplates] = useState<SessionTemplate[]>([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState(0);

    // Export state
    const [packName, setPackName] = useState("");
    const [packDescription, setPackDescription] = useState("");
    const [selectedActionTypes, setSelectedActionTypes] = useState<Set<number>>(new Set());
    const [selectedTools, setSelectedTools] = useState<Set<number>>(new Set());
    const [selectedToolsets, setSelectedToolsets] = useState<Set<number>>(new Set());
    const [selectedMcpServers, setSelectedMcpServers] = useState<Set<number>>(new Set());
    const [selectedReportDefs, setSelectedReportDefs] = useState<Set<number>>(new Set());
    const [selectedScheduledJobs, setSelectedScheduledJobs] = useState<Set<number>>(new Set());
    const [selectedSessionTemplates, setSelectedSessionTemplates] = useState<Set<string>>(new Set());
    const [exporting, setExporting] = useState(false);

    // Import state
    const [importFile, setImportFile] = useState<File | null>(null);
    const [importFilename, setImportFilename] = useState("");
    const [importPreview, setImportPreview] = useState<Record<string, number> | null>(null);
    const [importPackName, setImportPackName] = useState("");
    const [importing, setImporting] = useState(false);
    const [importResult, setImportResult] = useState<ImportResult | null>(null);
    const [importError, setImportError] = useState<string | null>(null);
    const [importConflicts, setImportConflicts] = useState<Array<{ type: string; name: string }> | null>(null);

    const loadData = useCallback(() => {
        setLoading(true);
        Promise.all([
            fetchActionTypes(1, 1000),
            fetchTools(1, 1000),
            fetchToolsets(),
            fetchMcpServers(),
            fetchReportDefinitions(),
            fetchScheduledJobs(),
            fetchAssistantTemplates(),
        ])
            .then(([at, t, ts, mcp, rd, sj, st]) => {
                setActionTypes(at.items);
                setTools(t.items);
                setToolsets(ts);
                setMcpServers(mcp);
                setReportDefs(rd);
                setScheduledJobs(sj);
                setSessionTemplates(st.filter((s: SessionTemplate) => !s.builtIn));
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadData(); }, [loadData]);

    const totalSelected = selectedActionTypes.size + selectedTools.size
        + selectedToolsets.size + selectedMcpServers.size + selectedReportDefs.size
        + selectedScheduledJobs.size + selectedSessionTemplates.size;

    const handleExport = async () => {
        setExporting(true);
        try {
            const request: PackExportRequest = {
                name: packName,
                description: packDescription || undefined,
                actionTypeIds: [...selectedActionTypes],
                toolIds: [...selectedTools],
                toolsetIds: [...selectedToolsets],
                mcpServerIds: [...selectedMcpServers],
                reportDefinitionIds: [...selectedReportDefs],
                scheduledJobIds: [...selectedScheduledJobs],
                sessionTemplateIds: [...selectedSessionTemplates],
            };
            const blob = await exportPack(request);
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = packName.replace(/[^a-zA-Z0-9_-]/g, "_") + ".json";
            a.click();
            URL.revokeObjectURL(url);
        } catch (e) {
            console.error(e);
        } finally {
            setExporting(false);
        }
    };

    const handleFileInputChange = (_event: unknown, file: File) => {
        setImportFile(file);
        setImportFilename(file.name);
        setImportResult(null);
        setImportError(null);
        setImportConflicts(null);
        if (file) {
            file.text().then((text) => {
                try {
                    const json = JSON.parse(text);
                    const preview: Record<string, number> = {};
                    if (json.actionTypes?.length) preview["Action Types"] = json.actionTypes.length;
                    if (json.tools?.length) preview["Tools"] = json.tools.length;
                    if (json.toolsets?.length) preview["Toolsets"] = json.toolsets.length;
                    if (json.mcpServers?.length) preview["MCP Servers"] = json.mcpServers.length;
                    if (json.reportDefinitions?.length) preview["Report Definitions"] = json.reportDefinitions.length;
                    if (json.scheduledJobs?.length) preview["Scheduled Jobs"] = json.scheduledJobs.length;
                    if (json.sessionTemplates?.length) preview["Session Templates"] = json.sessionTemplates.length;
                    setImportPreview(preview);
                    setImportPackName(json.metadata?.name || "");
                } catch {
                    setImportPreview(null);
                    setImportPackName("");
                    setImportError("Invalid JSON file");
                }
            });
        } else {
            setImportPreview(null);
        }
    };

    const handleFileClear = () => {
        setImportFile(null);
        setImportFilename("");
        setImportPreview(null);
        setImportPackName("");
        setImportResult(null);
        setImportError(null);
        setImportConflicts(null);
    };

    const handleImport = async () => {
        if (!importFile) return;
        setImporting(true);
        setImportResult(null);
        setImportError(null);
        setImportConflicts(null);
        try {
            const text = await importFile.text();
            const result = await importPack(text);
            setImportResult(result);
            setImportFile(null);
            setImportFilename("");
            setImportPreview(null);
            setImportPackName("");
            loadData();
        } catch (e: unknown) {
            if (e && typeof e === "object" && "status" in e && (e as unknown as { status: number }).status === 409) {
                setImportConflicts((e as unknown as { conflicts: Array<{ type: string; name: string }> }).conflicts);
            } else {
                setImportError(e instanceof Error ? e.message : String(e));
            }
        } finally {
            setImporting(false);
        }
    };

    const toggleSet = (set: Set<number>, id: number): Set<number> => {
        const next = new Set(set);
        if (next.has(id)) next.delete(id); else next.add(id);
        return next;
    };

    const collectToolsFromToolset = (ts: Toolset, toolIds: Set<number>) => {
        const toolPrefix = "mcp__axiom-tools__";
        for (const ref of ts.tools) {
            if (ref.startsWith(toolPrefix)) {
                const toolName = ref.substring(toolPrefix.length);
                const tool = tools.find((t) => t.name === toolName);
                if (tool) toolIds.add(tool.id);
            }
        }
    };

    const autoSelectReferencedItems = (allowedToolsLists: (string[] | undefined)[]) => {
        const toolPrefix = "mcp__axiom-tools__";
        const newToolIds = new Set(selectedTools);
        const newToolsetIds = new Set(selectedToolsets);

        for (const allowedTools of allowedToolsLists) {
            if (!allowedTools) continue;
            for (const ref of allowedTools) {
                if (ref.startsWith(toolPrefix)) {
                    const toolName = ref.substring(toolPrefix.length);
                    const tool = tools.find((t) => t.name === toolName);
                    if (tool) newToolIds.add(tool.id);
                } else if (ref.startsWith("@")) {
                    const tsName = ref.substring(1);
                    const ts = toolsets.find((t) => t.name === tsName);
                    if (ts) {
                        newToolsetIds.add(ts.id);
                        collectToolsFromToolset(ts, newToolIds);
                    }
                }
            }
        }

        if (newToolIds.size > selectedTools.size) setSelectedTools(newToolIds);
        if (newToolsetIds.size > selectedToolsets.size) setSelectedToolsets(newToolsetIds);
    };

    const autoSelectToolsFromToolsets = (toolsetIds: number[]) => {
        const newToolIds = new Set(selectedTools);
        for (const tsId of toolsetIds) {
            const ts = toolsets.find((t) => t.id === tsId);
            if (ts) collectToolsFromToolset(ts, newToolIds);
        }
        if (newToolIds.size > selectedTools.size) setSelectedTools(newToolIds);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg">
                Configuration Packs
            </Title>
            <p className="axiom-text-subtle" style={{ marginTop: "8px", marginBottom: "16px" }}>
                Configuration packs bundle related items — action types, tools, toolsets,
                MCP servers, report definitions, scheduled jobs, and session templates — into a portable JSON
                file. Create a pack to share your setup with others, or import one to quickly
                add pre-configured functionality to your Axiom instance.
            </p>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Create Pack</TabTitleText>}>
                    <TabContent id="create-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <div style={{ maxWidth: "700px" }}>
                            <Form>
                                <FormGroup label="Pack Name" isRequired fieldId="packName">
                                    <TextInput id="packName" isRequired value={packName}
                                        onChange={(_e, v) => setPackName(v)}
                                        placeholder="e.g. Weekly Status Report" />
                                </FormGroup>
                                <FormGroup label="Description" fieldId="packDescription">
                                    <TextArea id="packDescription" value={packDescription}
                                        onChange={(_e, v) => setPackDescription(v)}
                                        placeholder="What this pack contains and what it's for"
                                        rows={2} />
                                </FormGroup>
                            </Form>

                            <div style={{ marginTop: "24px" }}>
                                <SelectedItemsSection title="Action Types" allItems={actionTypes}
                                    selected={selectedActionTypes}
                                    onAdd={(ids) => {
                                        setSelectedActionTypes(new Set([...selectedActionTypes, ...ids]));
                                        autoSelectReferencedItems(
                                            ids.map((id) => actionTypes.find((a) => a.id === id)?.allowedTools));
                                    }}
                                    onRemove={(id) => { const next = new Set(selectedActionTypes); next.delete(id); setSelectedActionTypes(next); }}
                                    renderItem={(item) => {
                                        const at = actionTypes.find((a) => a.id === item.id);
                                        return (
                                            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "8px" }}>
                                                <FlexItem><span style={{ fontWeight: 600 }}>{item.name}</span></FlexItem>
                                                <FlexItem>
                                                    <Label isCompact color={at?.executionMode === "actor" ? "blue" : "orange"}>
                                                        {at?.executionMode || "—"}
                                                    </Label>
                                                </FlexItem>
                                                <FlexItem className="axiom-text-subtle" style={{ fontSize: "12px" }}>
                                                    {at?.executionMode === "actor" ? `${at?.allowedTools?.length || 0} tools` : ""}
                                                </FlexItem>
                                            </Flex>
                                        );
                                    }}
                                    renderModal={(props) => (
                                        <ActionTypePickerModal {...props}
                                            allActionTypes={actionTypes} />
                                    )} />
                                <SelectedItemsSection title="Tools" allItems={tools}
                                    selected={selectedTools}
                                    onAdd={(ids) => setSelectedTools(new Set([...selectedTools, ...ids]))}
                                    onRemove={(id) => { const next = new Set(selectedTools); next.delete(id); setSelectedTools(next); }}
                                    renderItem={(item) => {
                                        const t = tools.find((x) => x.id === item.id);
                                        return (
                                            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "8px" }}>
                                                <FlexItem><span style={{ fontWeight: 600 }}>{item.name}</span></FlexItem>
                                                <FlexItem className="axiom-text-subtle" style={{ fontSize: "12px" }}>
                                                    {t?.parameters?.length ? `${t.parameters.length} param(s)` : ""}
                                                </FlexItem>
                                            </Flex>
                                        );
                                    }}
                                    renderModal={(props) => (
                                        <ToolPickerModal {...props} allTools={tools} />
                                    )} />
                                <SelectedItemsSection title="Toolsets" allItems={toolsets}
                                    selected={selectedToolsets}
                                    onAdd={(ids) => {
                                        setSelectedToolsets(new Set([...selectedToolsets, ...ids]));
                                        autoSelectToolsFromToolsets(ids);
                                    }}
                                    onRemove={(id) => { const next = new Set(selectedToolsets); next.delete(id); setSelectedToolsets(next); }}
                                    renderItem={(item) => {
                                        const ts = toolsets.find((x) => x.id === item.id);
                                        return (
                                            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "8px" }}>
                                                <FlexItem><span style={{ fontWeight: 600 }}>{item.name}</span></FlexItem>
                                                <FlexItem className="axiom-text-subtle" style={{ fontSize: "12px" }}>
                                                    {ts?.tools?.length ? `${ts.tools.length} tool(s)` : ""}
                                                </FlexItem>
                                            </Flex>
                                        );
                                    }}
                                    renderModal={(props) => (
                                        <ToolsetPickerModal {...props} allToolsets={toolsets} />
                                    )} />
                                <CheckboxSection title="MCP Servers" items={mcpServers}
                                    selected={selectedMcpServers}
                                    onToggle={(id) => setSelectedMcpServers(toggleSet(selectedMcpServers, id))} />
                                <SelectedItemsSection title="Report Definitions" allItems={reportDefs}
                                    selected={selectedReportDefs}
                                    onAdd={(ids) => {
                                        setSelectedReportDefs(new Set([...selectedReportDefs, ...ids]));
                                        autoSelectReferencedItems(
                                            ids.map((id) => reportDefs.find((r) => r.id === id)?.allowedTools));
                                    }}
                                    onRemove={(id) => { const next = new Set(selectedReportDefs); next.delete(id); setSelectedReportDefs(next); }}
                                    renderItem={(item) => {
                                        const rd = reportDefs.find((x) => x.id === item.id);
                                        return (
                                            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "8px" }}>
                                                <FlexItem><span style={{ fontWeight: 600 }}>{item.name}</span></FlexItem>
                                                <FlexItem>
                                                    <Label isCompact>{rd?.schedule || "—"}</Label>
                                                </FlexItem>
                                                <FlexItem>
                                                    <Label isCompact color="grey">{rd?.timeWindow || "—"}</Label>
                                                </FlexItem>
                                            </Flex>
                                        );
                                    }}
                                    renderModal={(props) => (
                                        <ReportDefinitionPickerModal {...props} allReportDefs={reportDefs} />
                                    )} />
                                <SelectedItemsSection title="Scheduled Jobs" allItems={scheduledJobs}
                                    selected={selectedScheduledJobs}
                                    onAdd={(ids) => {
                                        setSelectedScheduledJobs(new Set([...selectedScheduledJobs, ...ids]));
                                        autoSelectReferencedItems(
                                            ids.map((id) => scheduledJobs.find((sj) => sj.id === id)?.allowedTools));
                                    }}
                                    onRemove={(id) => { const next = new Set(selectedScheduledJobs); next.delete(id); setSelectedScheduledJobs(next); }}
                                    renderItem={(item) => {
                                        const sj = scheduledJobs.find((x) => x.id === item.id);
                                        return (
                                            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "8px" }}>
                                                <FlexItem><span style={{ fontWeight: 600 }}>{item.name}</span></FlexItem>
                                                <FlexItem>
                                                    <Label isCompact>{sj?.schedule || "—"}</Label>
                                                </FlexItem>
                                                <FlexItem>
                                                    <Label isCompact color={sj?.enabled ? "green" : "grey"}>
                                                        {sj?.enabled ? "enabled" : "disabled"}
                                                    </Label>
                                                </FlexItem>
                                            </Flex>
                                        );
                                    }}
                                    renderModal={(props) => (
                                        <ScheduledJobPickerModal {...props} allScheduledJobs={scheduledJobs} />
                                    )} />
                                <SessionTemplateCheckboxSection
                                    templates={sessionTemplates}
                                    selected={selectedSessionTemplates}
                                    onToggle={(templateId) => {
                                        const next = new Set(selectedSessionTemplates);
                                        if (next.has(templateId)) next.delete(templateId); else next.add(templateId);
                                        setSelectedSessionTemplates(next);
                                        // Auto-select referenced MCP servers
                                        if (!next.has(templateId)) return;
                                        const st = sessionTemplates.find((s) => s.templateId === templateId);
                                        if (st?.mcpServers?.length) {
                                            const newMcpIds = new Set(selectedMcpServers);
                                            for (const serverName of st.mcpServers) {
                                                const server = mcpServers.find((m) => m.name === serverName);
                                                if (server) newMcpIds.add(server.id);
                                            }
                                            if (newMcpIds.size > selectedMcpServers.size) setSelectedMcpServers(newMcpIds);
                                        }
                                        // Auto-select referenced tools/toolsets
                                        if (st?.allowedTools?.length) {
                                            autoSelectReferencedItems([st.allowedTools]);
                                        }
                                    }} />
                            </div>

                            <div style={{ marginTop: "24px" }}>
                                <Button variant="primary" icon={<DownloadIcon />}
                                    onClick={handleExport}
                                    isDisabled={!packName.trim() || totalSelected === 0 || exporting}
                                    isLoading={exporting}>
                                    {exporting ? "Exporting..." : `Export Pack (${totalSelected} item${totalSelected !== 1 ? "s" : ""})`}
                                </Button>
                            </div>
                        </div>
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Import Pack</TabTitleText>}>
                    <TabContent id="import-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <div style={{ maxWidth: "600px" }}>
                            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                                Upload a configuration pack JSON file to import its contents.
                            </p>

                            <FileUpload
                                id="pack-file-upload"
                                value={importFile || undefined}
                                filename={importFilename}
                                onFileInputChange={handleFileInputChange}
                                onClearClick={handleFileClear}
                                browseButtonText="Choose file"
                                dropzoneProps={{ accept: { "application/json": [".json"] } }}
                                style={{ marginBottom: "16px" }}
                            />

                            {importPreview && (
                                <div style={{
                                    padding: "12px 16px",
                                    backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                    borderRadius: "4px",
                                    marginBottom: "16px",
                                }}>
                                    <div style={{ fontWeight: 600, marginBottom: "8px" }}>
                                        {importPackName || "Pack"} — contents:
                                    </div>
                                    {Object.entries(importPreview).map(([type, count]) => (
                                        <Flex key={type} justifyContent={{ default: "justifyContentSpaceBetween" }}
                                            style={{ marginBottom: "4px" }}>
                                            <FlexItem>{type}</FlexItem>
                                            <FlexItem>
                                                <Label isCompact color="blue">{count}</Label>
                                            </FlexItem>
                                        </Flex>
                                    ))}
                                </div>
                            )}

                            {importConflicts && (
                                <Alert variant="danger" title="Import failed — name conflicts detected"
                                    isInline style={{ marginBottom: "16px" }}>
                                    <p>The following items already exist:</p>
                                    <ul style={{ margin: "8px 0 0 16px" }}>
                                        {importConflicts.map((c, i) => (
                                            <li key={i}><strong>{c.type}</strong>: {c.name}</li>
                                        ))}
                                    </ul>
                                </Alert>
                            )}

                            {importError && (
                                <Alert variant="danger" title="Import failed" isInline
                                    style={{ marginBottom: "16px" }}>
                                    {importError}
                                </Alert>
                            )}

                            {importResult && (
                                <Alert variant="success" title="Pack imported successfully"
                                    isInline style={{ marginBottom: "16px" }}>
                                    <p>Imported:</p>
                                    <ul style={{ margin: "4px 0 0 16px" }}>
                                        {importResult.tools ? <li>{importResult.tools} tool(s)</li> : null}
                                        {importResult.toolsets ? <li>{importResult.toolsets} toolset(s)</li> : null}
                                        {importResult.mcpServers ? <li>{importResult.mcpServers} MCP server(s)</li> : null}
                                        {importResult.actionTypes ? <li>{importResult.actionTypes} action type(s)</li> : null}
                                        {importResult.reportDefinitions ? <li>{importResult.reportDefinitions} report definition(s)</li> : null}
                                        {importResult.scheduledJobs ? <li>{importResult.scheduledJobs} scheduled job(s)</li> : null}
                                        {importResult.sessionTemplates ? <li>{importResult.sessionTemplates} session template(s)</li> : null}
                                    </ul>
                                </Alert>
                            )}

                            <Button variant="primary" icon={<UploadIcon />}
                                onClick={handleImport}
                                isDisabled={!importFile || !importPreview || importing}
                                isLoading={importing}>
                                {importing ? "Importing..." : "Import"}
                            </Button>
                        </div>
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

function CheckboxSection({ title, items, selected, onToggle }: {
    title: string;
    items: Array<{ id: number; name: string; description?: string }>;
    selected: Set<number>;
    onToggle: (id: number) => void;
}) {
    if (items.length === 0) return null;

    const selectedCount = items.filter((i) => selected.has(i.id)).length;

    return (
        <div style={{ marginBottom: "16px" }}>
            <div style={{ fontWeight: 600, marginBottom: "8px", fontSize: "14px" }}>
                {title}
                {selectedCount > 0 && (
                    <Label isCompact color="blue" style={{ marginLeft: "8px" }}>
                        {selectedCount} selected
                    </Label>
                )}
            </div>
            <div style={{
                display: "flex", flexDirection: "column", gap: "4px",
                maxHeight: "200px", overflowY: "auto",
                padding: "8px 12px",
                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                borderRadius: "4px",
            }}>
                {items.map((item) => (
                    <Checkbox key={item.id}
                        id={`${title}-${item.id}`}
                        label={item.name}
                        description={item.description}
                        isChecked={selected.has(item.id)}
                        onChange={() => onToggle(item.id)} />
                ))}
            </div>
        </div>
    );
}

function SelectedItemsSection({ title, allItems, selected, onAdd, onRemove, renderModal, renderItem }: {
    title: string;
    allItems: Array<{ id: number; name: string; description?: string }>;
    selected: Set<number>;
    onAdd: (ids: number[]) => void;
    onRemove: (id: number) => void;
    renderItem?: (item: { id: number; name: string; description?: string }) => React.ReactNode;
    renderModal?: (props: {
        isOpen: boolean;
        onClose: () => void;
        availableItems: Array<{ id: number; name: string; description?: string }>;
        modalSelected: Set<number>;
        toggleItem: (id: number) => void;
        selectAll: (ids: number[]) => void;
        onConfirm: () => void;
    }) => React.ReactNode;
}) {
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [filter, setFilter] = useState("");
    const [modalSelected, setModalSelected] = useState<Set<number>>(new Set());

    const selectedItems = allItems.filter((i) => selected.has(i.id));

    const openModal = () => {
        setFilter("");
        setModalSelected(new Set());
        setIsModalOpen(true);
    };

    const handleConfirm = () => {
        onAdd([...modalSelected]);
        setIsModalOpen(false);
    };

    const availableItems = allItems.filter((i) => !selected.has(i.id));
    const filteredItems = filter
        ? availableItems.filter((i) =>
            i.name.toLowerCase().includes(filter.toLowerCase())
            || (i.description && i.description.toLowerCase().includes(filter.toLowerCase())))
        : availableItems;

    const toggleModalItem = (id: number) => {
        const next = new Set(modalSelected);
        if (next.has(id)) next.delete(id); else next.add(id);
        setModalSelected(next);
    };

    const selectAll = (ids: number[]) => {
        setModalSelected(new Set(ids));
    };

    return (
        <div style={{ marginBottom: "16px" }}>
            <Flex alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "8px" }}>
                <FlexItem>
                    <span style={{ fontWeight: 600, fontSize: "14px" }}>{title}</span>
                    {selectedItems.length > 0 && (
                        <Label isCompact color="blue" style={{ marginLeft: "8px" }}>
                            {selectedItems.length}
                        </Label>
                    )}
                </FlexItem>
                <FlexItem>
                    <Button variant="link" size="sm" icon={<PlusCircleIcon />}
                        onClick={openModal}
                        isDisabled={availableItems.length === 0}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>

            {selectedItems.length === 0 ? (
                <p className="axiom-text-subtle" style={{ fontSize: "13px", fontStyle: "italic" }}>
                    No {title.toLowerCase()} selected.
                </p>
            ) : (
                <SimpleList>
                    {selectedItems.map((item) => (
                        <SimpleListItem key={item.id}>
                            <Flex alignItems={{ default: "alignItemsCenter" }}>
                                <FlexItem grow={{ default: "grow" }}>
                                    {renderItem ? renderItem(item) : (
                                        <>
                                            <span style={{ fontWeight: 600 }}>{item.name}</span>
                                            {item.description && (
                                                <span className="axiom-text-subtle" style={{ fontSize: "12px", marginLeft: "8px" }}>
                                                    {item.description}
                                                </span>
                                            )}
                                        </>
                                    )}
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="plain" size="sm" style={{ padding: 0 }}
                                        onClick={(e) => { e.stopPropagation(); onRemove(item.id); }}>
                                        <TimesIcon />
                                    </Button>
                                </FlexItem>
                            </Flex>
                        </SimpleListItem>
                    ))}
                </SimpleList>
            )}

            {renderModal ? renderModal({
                isOpen: isModalOpen,
                onClose: () => setIsModalOpen(false),
                availableItems,
                modalSelected,
                toggleItem: toggleModalItem,
                selectAll,
                onConfirm: handleConfirm,
            }) : (
                <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)}
                    variant="medium" aria-label={`Add ${title}`}>
                    <ModalHeader title={`Add ${title}`} />
                    <ModalBody>
                        <TextInput
                            placeholder={`Filter ${title.toLowerCase()}...`}
                            value={filter}
                            onChange={(_e, v) => setFilter(v)}
                            aria-label={`Filter ${title}`}
                            style={{ marginBottom: "16px" }}
                        />
                        {filteredItems.length === 0 ? (
                            <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                                {availableItems.length === 0
                                    ? `All ${title.toLowerCase()} are already selected.`
                                    : "No items match the filter."}
                            </p>
                        ) : (
                            <div style={{
                                display: "flex", flexDirection: "column", gap: "4px",
                                maxHeight: "400px", overflowY: "auto",
                            }}>
                                {filteredItems.map((item) => (
                                    <Checkbox key={item.id}
                                        id={`modal-${title}-${item.id}`}
                                        label={item.name}
                                        description={item.description}
                                        isChecked={modalSelected.has(item.id)}
                                        onChange={() => toggleModalItem(item.id)} />
                                ))}
                            </div>
                        )}
                    </ModalBody>
                    <ModalFooter>
                        <Button variant="primary" onClick={handleConfirm}
                            isDisabled={modalSelected.size === 0}>
                            Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                        </Button>
                        <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                    </ModalFooter>
                </Modal>
            )}
        </div>
    );
}

function ActionTypePickerModal({ isOpen, onClose, availableItems, modalSelected, toggleItem, selectAll, onConfirm, allActionTypes }: {
    isOpen: boolean;
    onClose: () => void;
    availableItems: Array<{ id: number; name: string; description?: string }>;
    modalSelected: Set<number>;
    toggleItem: (id: number) => void;
    selectAll: (ids: number[]) => void;
    onConfirm: () => void;
    allActionTypes: ActionType[];
}) {
    const [filter, setFilter] = useState("");

    const availableActionTypes = allActionTypes.filter((at) =>
        availableItems.some((i) => i.id === at.id));
    const filtered = filter
        ? availableActionTypes.filter((at) =>
            at.name.toLowerCase().includes(filter.toLowerCase())
            || (at.description && at.description.toLowerCase().includes(filter.toLowerCase())))
        : availableActionTypes;

    const allFilteredSelected = filtered.length > 0 && filtered.every((at) => modalSelected.has(at.id));

    const handleSelectAll = () => {
        if (allFilteredSelected) {
            selectAll([...modalSelected].filter((id) => !filtered.some((at) => at.id === id)));
        } else {
            selectAll([...new Set([...modalSelected, ...filtered.map((at) => at.id)])]);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose}
            variant="large" aria-label="Add Action Types">
            <ModalHeader title="Add Action Types" />
            <ModalBody>
                <TextInput
                    placeholder="Filter action types..."
                    value={filter}
                    onChange={(_e, v) => setFilter(v)}
                    aria-label="Filter action types"
                    style={{ marginBottom: "16px" }}
                />
                {filtered.length === 0 ? (
                    <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                        {availableItems.length === 0
                            ? "All action types are already selected."
                            : "No action types match the filter."}
                    </p>
                ) : (
                    <Table aria-label="Action Types" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}>
                                    <Checkbox id="select-all-action-types"
                                        isChecked={allFilteredSelected}
                                        onChange={handleSelectAll}
                                        aria-label="Select all" />
                                </Th>
                                <Th>Name</Th>
                                <Th>Mode</Th>
                                <Th>Tools</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {filtered.map((at) => (
                                <Tr key={at.id}>
                                    <Td>
                                        <Checkbox id={`at-pick-${at.id}`}
                                            isChecked={modalSelected.has(at.id)}
                                            onChange={() => toggleItem(at.id)}
                                            aria-label={`Select ${at.name}`} />
                                    </Td>
                                    <Td>{at.name}</Td>
                                    <Td>
                                        <Label isCompact color={at.executionMode === "actor" ? "blue" : "orange"}>
                                            {at.executionMode}
                                        </Label>
                                    </Td>
                                    <Td>
                                        {at.executionMode === "actor"
                                            ? `${at.allowedTools?.length || 0} tools`
                                            : "—"}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onConfirm}
                    isDisabled={modalSelected.size === 0}>
                    Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}

function ToolPickerModal({ isOpen, onClose, availableItems, modalSelected, toggleItem, selectAll, onConfirm, allTools }: {
    isOpen: boolean;
    onClose: () => void;
    availableItems: Array<{ id: number; name: string }>;
    modalSelected: Set<number>;
    toggleItem: (id: number) => void;
    selectAll: (ids: number[]) => void;
    onConfirm: () => void;
    allTools: ToolDefinition[];
}) {
    const [filter, setFilter] = useState("");

    const available = allTools.filter((t) => availableItems.some((i) => i.id === t.id));
    const filtered = filter
        ? available.filter((t) =>
            t.name.toLowerCase().includes(filter.toLowerCase())
            || (t.description && t.description.toLowerCase().includes(filter.toLowerCase())))
        : available;

    const allFilteredSelected = filtered.length > 0 && filtered.every((t) => modalSelected.has(t.id));
    const handleSelectAll = () => {
        if (allFilteredSelected) {
            selectAll([...modalSelected].filter((id) => !filtered.some((t) => t.id === id)));
        } else {
            selectAll([...new Set([...modalSelected, ...filtered.map((t) => t.id)])]);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Add Tools">
            <ModalHeader title="Add Tools" />
            <ModalBody>
                <TextInput placeholder="Filter tools..." value={filter}
                    onChange={(_e, v) => setFilter(v)} aria-label="Filter tools"
                    style={{ marginBottom: "16px" }} />
                {filtered.length === 0 ? (
                    <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                        {available.length === 0 ? "All tools are already selected." : "No tools match the filter."}
                    </p>
                ) : (
                    <Table aria-label="Tools" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}><Checkbox id="select-all-tools" isChecked={allFilteredSelected}
                                    onChange={handleSelectAll} aria-label="Select all" /></Th>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Parameters</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {filtered.map((t) => (
                                <Tr key={t.id}>
                                    <Td><Checkbox id={`tool-pick-${t.id}`} isChecked={modalSelected.has(t.id)}
                                        onChange={() => toggleItem(t.id)} aria-label={`Select ${t.name}`} /></Td>
                                    <Td>{t.name}</Td>
                                    <Td className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                        {t.description ? (t.description.length > 60 ? t.description.substring(0, 57) + "..." : t.description) : "—"}
                                    </Td>
                                    <Td>{t.parameters?.length || 0}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onConfirm} isDisabled={modalSelected.size === 0}>
                    Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}

function ToolsetPickerModal({ isOpen, onClose, availableItems, modalSelected, toggleItem, selectAll, onConfirm, allToolsets }: {
    isOpen: boolean;
    onClose: () => void;
    availableItems: Array<{ id: number; name: string }>;
    modalSelected: Set<number>;
    toggleItem: (id: number) => void;
    selectAll: (ids: number[]) => void;
    onConfirm: () => void;
    allToolsets: Toolset[];
}) {
    const [filter, setFilter] = useState("");

    const available = allToolsets.filter((ts) => availableItems.some((i) => i.id === ts.id));
    const filtered = filter
        ? available.filter((ts) =>
            ts.name.toLowerCase().includes(filter.toLowerCase())
            || (ts.description && ts.description.toLowerCase().includes(filter.toLowerCase())))
        : available;

    const allFilteredSelected = filtered.length > 0 && filtered.every((ts) => modalSelected.has(ts.id));
    const handleSelectAll = () => {
        if (allFilteredSelected) {
            selectAll([...modalSelected].filter((id) => !filtered.some((ts) => ts.id === id)));
        } else {
            selectAll([...new Set([...modalSelected, ...filtered.map((ts) => ts.id)])]);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Add Toolsets">
            <ModalHeader title="Add Toolsets" />
            <ModalBody>
                <TextInput placeholder="Filter toolsets..." value={filter}
                    onChange={(_e, v) => setFilter(v)} aria-label="Filter toolsets"
                    style={{ marginBottom: "16px" }} />
                {filtered.length === 0 ? (
                    <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                        {available.length === 0 ? "All toolsets are already selected." : "No toolsets match the filter."}
                    </p>
                ) : (
                    <Table aria-label="Toolsets" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}><Checkbox id="select-all-toolsets" isChecked={allFilteredSelected}
                                    onChange={handleSelectAll} aria-label="Select all" /></Th>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Tools</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {filtered.map((ts) => (
                                <Tr key={ts.id}>
                                    <Td><Checkbox id={`ts-pick-${ts.id}`} isChecked={modalSelected.has(ts.id)}
                                        onChange={() => toggleItem(ts.id)} aria-label={`Select ${ts.name}`} /></Td>
                                    <Td>{ts.name}</Td>
                                    <Td className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                        {ts.description || "—"}
                                    </Td>
                                    <Td>{ts.tools?.length || 0}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onConfirm} isDisabled={modalSelected.size === 0}>
                    Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}

function SessionTemplateCheckboxSection({ templates, selected, onToggle }: {
    templates: SessionTemplate[];
    selected: Set<string>;
    onToggle: (templateId: string) => void;
}) {
    if (templates.length === 0) return null;

    const selectedCount = templates.filter((t) => selected.has(t.templateId)).length;

    return (
        <div style={{ marginBottom: "16px" }}>
            <div style={{ fontWeight: 600, marginBottom: "8px", fontSize: "14px" }}>
                Session Templates
                {selectedCount > 0 && (
                    <Label isCompact color="blue" style={{ marginLeft: "8px" }}>
                        {selectedCount} selected
                    </Label>
                )}
            </div>
            <div style={{
                display: "flex", flexDirection: "column", gap: "4px",
                maxHeight: "200px", overflowY: "auto",
                padding: "8px 12px",
                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                borderRadius: "4px",
            }}>
                {templates.map((t) => (
                    <Checkbox key={t.templateId}
                        id={`session-template-${t.templateId}`}
                        label={t.name}
                        description={t.description}
                        isChecked={selected.has(t.templateId)}
                        onChange={() => onToggle(t.templateId)} />
                ))}
            </div>
        </div>
    );
}

function ReportDefinitionPickerModal({ isOpen, onClose, availableItems, modalSelected, toggleItem, selectAll, onConfirm, allReportDefs }: {
    isOpen: boolean;
    onClose: () => void;
    availableItems: Array<{ id: number; name: string }>;
    modalSelected: Set<number>;
    toggleItem: (id: number) => void;
    selectAll: (ids: number[]) => void;
    onConfirm: () => void;
    allReportDefs: ReportDefinition[];
}) {
    const [filter, setFilter] = useState("");

    const available = allReportDefs.filter((rd) => availableItems.some((i) => i.id === rd.id));
    const filtered = filter
        ? available.filter((rd) =>
            rd.name.toLowerCase().includes(filter.toLowerCase())
            || (rd.description && rd.description.toLowerCase().includes(filter.toLowerCase())))
        : available;

    const allFilteredSelected = filtered.length > 0 && filtered.every((rd) => modalSelected.has(rd.id));
    const handleSelectAll = () => {
        if (allFilteredSelected) {
            selectAll([...modalSelected].filter((id) => !filtered.some((rd) => rd.id === id)));
        } else {
            selectAll([...new Set([...modalSelected, ...filtered.map((rd) => rd.id)])]);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Add Report Definitions">
            <ModalHeader title="Add Report Definitions" />
            <ModalBody>
                <TextInput placeholder="Filter report definitions..." value={filter}
                    onChange={(_e, v) => setFilter(v)} aria-label="Filter report definitions"
                    style={{ marginBottom: "16px" }} />
                {filtered.length === 0 ? (
                    <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                        {available.length === 0 ? "All report definitions are already selected." : "No report definitions match the filter."}
                    </p>
                ) : (
                    <Table aria-label="Report Definitions" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}><Checkbox id="select-all-reportdefs" isChecked={allFilteredSelected}
                                    onChange={handleSelectAll} aria-label="Select all" /></Th>
                                <Th>Name</Th>
                                <Th>Schedule</Th>
                                <Th>Time Window</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {filtered.map((rd) => (
                                <Tr key={rd.id}>
                                    <Td><Checkbox id={`rd-pick-${rd.id}`} isChecked={modalSelected.has(rd.id)}
                                        onChange={() => toggleItem(rd.id)} aria-label={`Select ${rd.name}`} /></Td>
                                    <Td>{rd.name}</Td>
                                    <Td><Label isCompact>{rd.schedule}</Label></Td>
                                    <Td><Label isCompact color="grey">{rd.timeWindow}</Label></Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onConfirm} isDisabled={modalSelected.size === 0}>
                    Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}

function ScheduledJobPickerModal({ isOpen, onClose, availableItems, modalSelected, toggleItem, selectAll, onConfirm, allScheduledJobs }: {
    isOpen: boolean;
    onClose: () => void;
    availableItems: Array<{ id: number; name: string }>;
    modalSelected: Set<number>;
    toggleItem: (id: number) => void;
    selectAll: (ids: number[]) => void;
    onConfirm: () => void;
    allScheduledJobs: ScheduledJob[];
}) {
    const [filter, setFilter] = useState("");

    const available = allScheduledJobs.filter((sj) => availableItems.some((i) => i.id === sj.id));
    const filtered = filter
        ? available.filter((sj) =>
            sj.name.toLowerCase().includes(filter.toLowerCase())
            || (sj.description && sj.description.toLowerCase().includes(filter.toLowerCase())))
        : available;

    const allFilteredSelected = filtered.length > 0 && filtered.every((sj) => modalSelected.has(sj.id));
    const handleSelectAll = () => {
        if (allFilteredSelected) {
            selectAll([...modalSelected].filter((id) => !filtered.some((sj) => sj.id === id)));
        } else {
            selectAll([...new Set([...modalSelected, ...filtered.map((sj) => sj.id)])]);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Add Scheduled Jobs">
            <ModalHeader title="Add Scheduled Jobs" />
            <ModalBody>
                <TextInput placeholder="Filter scheduled jobs..." value={filter}
                    onChange={(_e, v) => setFilter(v)} aria-label="Filter scheduled jobs"
                    style={{ marginBottom: "16px" }} />
                {filtered.length === 0 ? (
                    <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                        {available.length === 0 ? "All scheduled jobs are already selected." : "No scheduled jobs match the filter."}
                    </p>
                ) : (
                    <Table aria-label="Scheduled Jobs" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}><Checkbox id="select-all-scheduledjobs" isChecked={allFilteredSelected}
                                    onChange={handleSelectAll} aria-label="Select all" /></Th>
                                <Th>Name</Th>
                                <Th>Schedule</Th>
                                <Th>Status</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {filtered.map((sj) => (
                                <Tr key={sj.id}>
                                    <Td><Checkbox id={`sj-pick-${sj.id}`} isChecked={modalSelected.has(sj.id)}
                                        onChange={() => toggleItem(sj.id)} aria-label={`Select ${sj.name}`} /></Td>
                                    <Td>{sj.name}</Td>
                                    <Td><Label isCompact>{sj.schedule}</Label></Td>
                                    <Td>
                                        <Label isCompact color={sj.enabled ? "green" : "grey"}>
                                            {sj.enabled ? "enabled" : "disabled"}
                                        </Label>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onConfirm} isDisabled={modalSelected.size === 0}>
                    Add {modalSelected.size > 0 ? `(${modalSelected.size})` : ""}
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
