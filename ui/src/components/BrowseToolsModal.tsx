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
import { SDK_TOOLS as SDK_TOOL_ENTRIES } from "../config/sdkTools";

interface ToolEntry {
    value: string;
    label: string;
    description?: string;
    category: "toolset" | "custom" | "sdk";
}

const SDK_TOOLS: ToolEntry[] = SDK_TOOL_ENTRIES.map(t => ({ ...t, category: "sdk" as const }));

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
