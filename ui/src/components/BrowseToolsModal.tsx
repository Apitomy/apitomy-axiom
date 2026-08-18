import { useState, useEffect } from "react";
import {
    Button,
    Checkbox,
    ExpandableSection,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    TextInput,
} from "@patternfly/react-core";
import SearchIcon from "@patternfly/react-icons/dist/esm/icons/search-icon";
import {
    fetchToolsets,
    fetchTools,
    type Toolset,
    type ToolDefinition,
} from "../config/api";

interface ToolEntry {
    value: string;
    label: string;
    description?: string;
    category: "toolset" | "custom" | "sdk";
}

const SDK_TOOLS: ToolEntry[] = [
    { value: "mcp__axiom-sdk__axiom_fire_event", label: "axiom_fire_event", description: "Fire a new event into Axiom for processing by the Manager", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_list_projects", label: "axiom_list_projects", description: "List existing Axiom projects with optional filtering", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_get_project", label: "axiom_get_project", description: "Get details of a specific Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_create_task", label: "axiom_create_task", description: "Create a new task on an Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_get_task_status", label: "axiom_get_task_status", description: "Get the status and details of a specific task", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_add_thread_entry", label: "axiom_add_thread_entry", description: "Post an update or message to a project's conversation thread", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_close_project", label: "axiom_close_project", description: "Close (complete) an Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_reopen_project", label: "axiom_reopen_project", description: "Reopen a previously closed Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_add_project_label", label: "axiom_add_project_label", description: "Add a label to an Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_remove_project_label", label: "axiom_remove_project_label", description: "Remove a label from an Axiom project", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_list_tools", label: "axiom_list_tools", description: "List all custom tool definitions configured in Axiom", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_list_report_definitions", label: "axiom_list_report_definitions", description: "List all report definitions configured in Axiom", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_update_project", label: "axiom_update_project", description: "Update an Axiom project's metadata such as name, body, or labels", category: "sdk" },
    { value: "mcp__axiom-sdk__axiom_update_project_body", label: "axiom_update_project_body", description: "Update an Axiom project's body with markdown content", category: "sdk" },
];

interface BrowseToolsModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSave: (tools: string[]) => void;
    existingTools: string[];
}

export function BrowseToolsModal({ isOpen, onClose, onSave, existingTools }: BrowseToolsModalProps) {
    const [toolsets, setToolsets] = useState<ToolEntry[]>([]);
    const [customTools, setCustomTools] = useState<ToolEntry[]>([]);
    const [selected, setSelected] = useState<Set<string>>(new Set());
    const [filter, setFilter] = useState("");
    const [toolsetsExpanded, setToolsetsExpanded] = useState(true);
    const [customExpanded, setCustomExpanded] = useState(true);
    const [sdkExpanded, setSdkExpanded] = useState(true);

    useEffect(() => {
        if (!isOpen) return;
        setSelected(new Set(existingTools));
        setFilter("");

        Promise.all([fetchToolsets(), fetchTools(1, 1000)])
            .then(([ts, toolsResult]) => {
                setToolsets(ts.map((t: Toolset) => ({
                    value: `@${t.name}`,
                    label: `@${t.name}`,
                    description: t.description || undefined,
                    category: "toolset" as const,
                })));
                setCustomTools(toolsResult.items.map((t: ToolDefinition) => ({
                    value: `mcp__axiom-tools__${t.name}`,
                    label: t.name,
                    description: t.description || undefined,
                    category: "custom" as const,
                })));
            })
            .catch(console.error);
    }, [isOpen, existingTools]);

    const matchesFilter = (entry: ToolEntry) => {
        if (!filter) return true;
        const lf = filter.toLowerCase();
        return entry.value.toLowerCase().includes(lf)
            || entry.label.toLowerCase().includes(lf)
            || (entry.description?.toLowerCase().includes(lf) ?? false);
    };

    const filteredToolsets = toolsets.filter(matchesFilter);
    const filteredCustom = customTools.filter(matchesFilter);
    const filteredSdk = SDK_TOOLS.filter(matchesFilter);

    const toggleSelection = (value: string) => {
        setSelected((prev) => {
            const next = new Set(prev);
            if (next.has(value)) {
                next.delete(value);
            } else {
                next.add(value);
            }
            return next;
        });
    };

    const handleSave = () => {
        onSave(Array.from(selected));
        onClose();
    };

    const renderEntry = (entry: ToolEntry) => {
        const isChecked = selected.has(entry.value);

        return (
            <div key={entry.value} style={{
                padding: "6px 8px",
                borderBottom: "1px solid var(--pf-t--global--border--color--default, #d2d2d2)",
            }}>
                <Checkbox
                    id={`browse-${entry.value}`}
                    isChecked={isChecked}
                    onChange={() => toggleSelection(entry.value)}
                    label={
                        <span>
                            <span style={
                                entry.category === "toolset"
                                    ? { color: "var(--pf-t--global--color--brand--default)", fontWeight: 500 }
                                    : { fontFamily: "var(--pf-t--global--font--family--mono)", fontSize: "13px" }
                            }>
                                {entry.label}
                            </span>
                            {entry.description && (
                                <span className="axiom-text-subtle" style={{ fontSize: "12px", marginLeft: 8 }}>
                                    — {entry.description}
                                </span>
                            )}
                        </span>
                    }
                />
            </div>
        );
    };

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            variant="medium"
            aria-label="Manage Allowed Tools"
            style={{ height: "80vh" }}
        >
            <ModalHeader title="Manage Allowed Tools" />
            <ModalBody style={{ overflow: "auto" }}>
                <div style={{ marginBottom: 16 }}>
                    <TextInput
                        value={filter}
                        onChange={(_e, val) => setFilter(val)}
                        placeholder="Filter tools..."
                        aria-label="Filter tools"
                        customIcon={<SearchIcon />}
                    />
                </div>

                {filteredToolsets.length > 0 && (
                    <ExpandableSection
                        toggleText={`Toolsets (${filteredToolsets.length})`}
                        isExpanded={toolsetsExpanded}
                        onToggle={(_e, expanded) => setToolsetsExpanded(expanded)}
                        style={{ marginBottom: 8 }}
                    >
                        {filteredToolsets.map(renderEntry)}
                    </ExpandableSection>
                )}

                {filteredCustom.length > 0 && (
                    <ExpandableSection
                        toggleText={`Custom Tools (${filteredCustom.length})`}
                        isExpanded={customExpanded}
                        onToggle={(_e, expanded) => setCustomExpanded(expanded)}
                        style={{ marginBottom: 8 }}
                    >
                        {filteredCustom.map(renderEntry)}
                    </ExpandableSection>
                )}

                {filteredSdk.length > 0 && (
                    <ExpandableSection
                        toggleText={`Axiom SDK Tools (${filteredSdk.length})`}
                        isExpanded={sdkExpanded}
                        onToggle={(_e, expanded) => setSdkExpanded(expanded)}
                    >
                        {filteredSdk.map(renderEntry)}
                    </ExpandableSection>
                )}

                {filteredToolsets.length === 0 && filteredCustom.length === 0 && filteredSdk.length === 0 && (
                    <div className="axiom-text-subtle" style={{ padding: 24, textAlign: "center" }}>
                        No tools match the filter.
                    </div>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={handleSave}>
                    Save
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
