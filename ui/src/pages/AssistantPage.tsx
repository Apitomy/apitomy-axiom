import { useState, useEffect, useCallback, useRef } from "react";
import { useNavigate } from "react-router-dom";
import {
    PageSection,
    Content,
    Button,
    EmptyState,
    EmptyStateBody,
    EmptyStateFooter,
    EmptyStateActions,
    Spinner,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    TextInput,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
    ToolbarGroup,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Alert,
    SearchInput,
} from "@patternfly/react-core";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    fetchAssistantSessions,
    createAssistantSession,
    deleteAssistantSession,
    renameAssistantSession,
    fetchAssistantTemplates,
    type AssistantSessionInfo,
    type SessionTemplate,
} from "../config/api";

const FUN_WORDS = [
    "rocket", "cactus", "penguin", "waffle", "thunder", "mango", "cosmic",
    "pickle", "turbo", "noodle", "galaxy", "biscuit", "phantom", "pretzel",
    "velvet", "zigzag", "bamboo", "coral", "doodle", "falcon", "gopher",
    "cobalt", "marble", "nimbus", "orchid", "quartz", "walrus", "tundra",
    "nebula", "pebble", "saffron", "breeze", "mosaic", "lantern", "crimson",
    "meadow", "jasper", "harbor", "ember", "frost", "summit", "canyon",
    "copper", "willow", "sparrow", "clover", "rapids", "flint", "comet",
    "puzzle", "sphinx", "goblin", "dragon", "wizard", "pirate", "ninja",
    "viking", "yeti", "kraken", "phoenix", "griffin", "titan", "tempest",
    "aurora", "blizzard", "cascade", "dynamo", "eclipse", "forge", "horizon",
];

function generateSessionName(): string {
    const pick = () => FUN_WORDS[Math.floor(Math.random() * FUN_WORDS.length)];
    return `${pick()}-${pick()}-${pick()}`;
}

const STATUS_COLORS: Record<string, "blue" | "green" | "red" | "grey"> = {
    starting: "blue",
    running: "green",
    stopped: "grey",
    error: "red",
};

export function AssistantPage() {
    const navigate = useNavigate();
    const [sessions, setSessions] = useState<AssistantSessionInfo[]>([]);
    const [loading, setLoading] = useState(true);
    const [templates, setTemplates] = useState<SessionTemplate[]>([]);
    const [isTemplatePickerOpen, setIsTemplatePickerOpen] = useState(false);
    const [templateFilter, setTemplateFilter] = useState("");
    const [selectedTemplate, setSelectedTemplate] = useState<SessionTemplate | null>(null);
    const [isNameModalOpen, setIsNameModalOpen] = useState(false);
    const [newName, setNewName] = useState("");
    const createButtonRef = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (isNameModalOpen) {
            setTimeout(() => createButtonRef.current?.focus(), 100);
        }
    }, [isNameModalOpen]);
    const [creating, setCreating] = useState(false);
    const [createError, setCreateError] = useState("");

    const [filterName, setFilterName] = useState("");
    const [filterTemplateId, setFilterTemplateId] = useState("");
    const [templateMap, setTemplateMap] = useState<Record<string, string>>({});

    const load = useCallback(() => {
        setLoading(true);
        Promise.all([fetchAssistantSessions(), fetchAssistantTemplates()])
            .then(([sess, tmpls]) => {
                setSessions(sess);
                const map: Record<string, string> = {};
                tmpls.forEach((t) => { map[t.templateId] = t.name; });
                setTemplateMap(map);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const openTemplatePicker = () => {
        fetchAssistantTemplates()
            .then(setTemplates)
            .catch(console.error);
        setTemplateFilter("");
        setIsTemplatePickerOpen(true);
    };

    const filteredTemplates = templates.filter((t) => {
        if (!templateFilter) return true;
        const lower = templateFilter.toLowerCase();
        return t.name.toLowerCase().includes(lower)
            || t.description.toLowerCase().includes(lower);
    });

    const handleTemplateSelect = (template: SessionTemplate) => {
        setSelectedTemplate(template);
        setIsTemplatePickerOpen(false);
        setNewName(generateSessionName());
        setIsNameModalOpen(true);
    };

    const handleCreate = async () => {
        if (!selectedTemplate) return;
        setCreating(true);
        setCreateError("");
        try {
            const session = await createAssistantSession(selectedTemplate.templateId, newName || undefined);
            setIsNameModalOpen(false);
            setNewName("");
            setSelectedTemplate(null);
            navigate(`/assistant/${session.id}`);
        } catch (err: unknown) {
            const e = err as { message?: string };
            setCreateError(e.message || "Failed to create session");
        } finally {
            setCreating(false);
        }
    };

    const filteredSessions = sessions.filter((s) => {
        if (filterName && !s.name.toLowerCase().includes(filterName.toLowerCase())) {
            return false;
        }
        if (filterTemplateId && s.templateId !== filterTemplateId) {
            return false;
        }
        return true;
    });

    const uniqueTemplateIds = [...new Set(sessions.map((s) => s.templateId))];

    const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

    const [renameTarget, setRenameTarget] = useState<{ id: string; name: string } | null>(null);
    const [renameValue, setRenameValue] = useState("");

    const handleRenameClick = (session: AssistantSessionInfo, e: React.MouseEvent) => {
        e.stopPropagation();
        setRenameTarget({ id: session.id, name: session.name });
        setRenameValue(session.name);
    };

    const handleRenameConfirm = async () => {
        if (!renameTarget || !renameValue.trim()) return;
        try {
            await renameAssistantSession(renameTarget.id, renameValue.trim());
            setRenameTarget(null);
            load();
        } catch (err) {
            console.error("Failed to rename session:", err);
        }
    };

    const handleDeleteClick = (id: string, e: React.MouseEvent) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const handleDeleteConfirm = async () => {
        if (!deleteTarget) return;
        try {
            await deleteAssistantSession(deleteTarget);
            setDeleteTarget(null);
            load();
        } catch (err) {
            console.error("Failed to delete session:", err);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Content component="h1"><RobotIcon style={{ marginRight: 8 }} />AI Assistant</Content>
            <Content component="p" style={{ marginBottom: 16 }}>
                Interactive AI assistant for creating related sets of Axiom configuration items.
            </Content>

            {!loading && sessions.length > 0 && (
                <Toolbar>
                    <ToolbarContent>
                        <ToolbarGroup>
                            <ToolbarItem>
                                <SearchInput
                                    placeholder="Filter by name..."
                                    value={filterName}
                                    onChange={(_e, v) => setFilterName(v)}
                                    onClear={() => setFilterName("")}
                                    style={{ width: 250 }}
                                />
                            </ToolbarItem>
                            <ToolbarItem>
                                <FormSelect
                                    value={filterTemplateId}
                                    onChange={(_e, v) => setFilterTemplateId(v)}
                                    aria-label="Filter by template"
                                    style={{ width: 220 }}
                                >
                                    <FormSelectOption value="" label="All templates" />
                                    {uniqueTemplateIds.map((tid) => (
                                        <FormSelectOption
                                            key={tid}
                                            value={tid}
                                            label={templateMap[tid] || tid}
                                        />
                                    ))}
                                </FormSelect>
                            </ToolbarItem>
                            <ToolbarItem>
                                <Button
                                    variant="plain"
                                    aria-label="Refresh sessions"
                                    onClick={load}
                                >
                                    <SyncAltIcon />
                                </Button>
                            </ToolbarItem>
                        </ToolbarGroup>
                        <ToolbarItem align={{ default: "alignEnd" }}>
                            <Button
                                variant="primary"
                                icon={<PlusCircleIcon />}
                                onClick={openTemplatePicker}
                            >
                                New Session
                            </Button>
                        </ToolbarItem>
                    </ToolbarContent>
                </Toolbar>
            )}

            {loading ? (
                <EmptyState variant="lg">
                    <Spinner size="lg" />
                    <EmptyStateBody>Loading sessions...</EmptyStateBody>
                </EmptyState>
            ) : sessions.length === 0 ? (
                <EmptyState
                    headingLevel="h2"
                    titleText="No active sessions"
                    icon={RobotIcon}
                    variant="lg"
                >
                    <EmptyStateBody>
                        Start an interactive AI assistant session to create related sets
                        of tools, action types, and report definitions through conversation.
                    </EmptyStateBody>
                    <EmptyStateFooter>
                        <EmptyStateActions>
                            <Button
                                variant="primary"
                                icon={<PlusCircleIcon />}
                                onClick={openTemplatePicker}
                            >
                                New Session
                            </Button>
                        </EmptyStateActions>
                    </EmptyStateFooter>
                </EmptyState>
            ) : (
                <div style={{ marginTop: 16 }}>
                    {filteredSessions.map((s) => (
                        <div
                            key={s.id}
                            onClick={() => navigate(`/assistant/${s.id}`)}
                            style={{
                                display: "flex",
                                alignItems: "center",
                                gap: 12,
                                padding: "14px 16px",
                                marginBottom: 8,
                                borderRadius: 8,
                                border: "1px solid #d2d2d2",
                                cursor: "pointer",
                                backgroundColor: "#fafafa",
                            }}
                            onMouseEnter={(e) => {
                                e.currentTarget.style.backgroundColor = "#f0f0f0";
                            }}
                            onMouseLeave={(e) => {
                                e.currentTarget.style.backgroundColor = "#fafafa";
                            }}
                        >
                            <div style={{ flex: 1 }}>
                                <div style={{ fontWeight: 600, fontSize: "14px" }}>{s.name}</div>
                                <div style={{ fontSize: "12px", color: "#6a6e73", marginTop: 2 }}>
                                    {templateMap[s.templateId] || s.templateId}
                                    {s.projectName && (
                                        <>
                                            {" · "}
                                            <Label isCompact color="teal">
                                                {s.projectName}
                                            </Label>
                                        </>
                                    )}
                                    {" · "}
                                    Created {new Date(s.createdAt).toLocaleString()}
                                </div>
                            </div>
                            <Label isCompact color={STATUS_COLORS[s.status] || "grey"}>
                                {s.status}
                            </Label>
                            <Button
                                variant="plain"
                                aria-label="Rename session"
                                onClick={(e) => handleRenameClick(s, e)}
                            >
                                <PencilAltIcon />
                            </Button>
                            <Button
                                variant="plain"
                                aria-label="Delete session"
                                onClick={(e) => handleDeleteClick(s.id, e)}
                            >
                                <TrashIcon />
                            </Button>
                        </div>
                    ))}
                </div>
            )}

            <Modal
                isOpen={isTemplatePickerOpen}
                onClose={() => setIsTemplatePickerOpen(false)}
                variant="medium"
                aria-label="Choose template"
            >
                <ModalHeader title="Choose a Template" />
                <ModalBody>
                    <SearchInput
                        placeholder="Filter templates..."
                        value={templateFilter}
                        onChange={(_e, v) => setTemplateFilter(v)}
                        onClear={() => setTemplateFilter("")}
                        style={{ marginBottom: 12 }}
                    />
                    <div style={{ maxHeight: 400, overflowY: "auto" }}>
                        {filteredTemplates.length === 0 ? (
                            <div style={{ padding: 24, textAlign: "center", color: "#6a6e73" }}>
                                No templates match your filter.
                            </div>
                        ) : (
                            filteredTemplates.map((t) => (
                                <div
                                    key={t.templateId}
                                    onClick={() => handleTemplateSelect(t)}
                                    style={{
                                        padding: "12px 16px",
                                        cursor: "pointer",
                                        borderBottom: "1px solid #eee",
                                    }}
                                    onMouseEnter={(e) =>
                                        (e.currentTarget.style.backgroundColor = "#f0f0f0")
                                    }
                                    onMouseLeave={(e) =>
                                        (e.currentTarget.style.backgroundColor = "transparent")
                                    }
                                >
                                    <div style={{ fontWeight: 600, fontSize: 14 }}>
                                        {t.name}
                                    </div>
                                    {t.description && (
                                        <div style={{ fontSize: 13, color: "#6a6e73", marginTop: 2 }}>
                                            {t.description}
                                        </div>
                                    )}
                                </div>
                            ))
                        )}
                    </div>
                </ModalBody>
            </Modal>

            <Modal
                isOpen={isNameModalOpen}
                onClose={() => setIsNameModalOpen(false)}
                variant="small"
                aria-label="Name session"
            >
                <ModalHeader title="Name Your Session" />
                <ModalBody>
                    {createError && (
                        <Alert variant="danger" isInline title={createError}
                            style={{ marginBottom: 12 }} />
                    )}
                    <Form>
                        <FormGroup label="Session Name" fieldId="session-name">
                            <TextInput
                                id="session-name"
                                value={newName}
                                onChange={(_e, v) => setNewName(v)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") handleCreate();
                                }}
                            />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button
                        ref={createButtonRef}
                        variant="primary"
                        onClick={handleCreate}
                        isLoading={creating}
                        isDisabled={creating}
                        autoFocus
                    >
                        Create Session
                    </Button>
                    <Button variant="link" onClick={() => setIsNameModalOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <Modal
                isOpen={deleteTarget !== null}
                onClose={() => setDeleteTarget(null)}
                variant="small"
                aria-label="End session confirmation"
            >
                <ModalHeader title="End Session?" />
                <ModalBody>
                    This will terminate the AI assistant and delete the session.
                    This action cannot be undone.
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={handleDeleteConfirm}>
                        End Session
                    </Button>
                    <Button variant="link" onClick={() => setDeleteTarget(null)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <Modal
                isOpen={renameTarget !== null}
                onClose={() => setRenameTarget(null)}
                variant="small"
                aria-label="Rename session"
            >
                <ModalHeader title="Rename Session" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Session Name" fieldId="rename-session">
                            <TextInput
                                id="rename-session"
                                value={renameValue}
                                onChange={(_e, v) => setRenameValue(v)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") handleRenameConfirm();
                                }}
                                autoFocus
                            />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleRenameConfirm}
                        isDisabled={!renameValue.trim()}>
                        Rename
                    </Button>
                    <Button variant="link" onClick={() => setRenameTarget(null)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
