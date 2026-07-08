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
    Form,
    FormGroup,
    Alert,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    fetchAssistantSessions,
    createAssistantSession,
    deleteAssistantSession,
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

    const load = useCallback(() => {
        setLoading(true);
        fetchAssistantSessions()
            .then(setSessions)
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
        setIsTemplatePickerOpen(true);
    };

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

    const handleDelete = async (id: string, e: React.MouseEvent) => {
        e.stopPropagation();
        if (!confirm("End this assistant session?")) return;
        try {
            await deleteAssistantSession(id);
            load();
        } catch (err) {
            console.error("Failed to delete session:", err);
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
                        <ToolbarItem>
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
                    {sessions.map((s) => (
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
                                    Created {new Date(s.createdAt).toLocaleString()}
                                </div>
                            </div>
                            <Label isCompact color={STATUS_COLORS[s.status] || "grey"}>
                                {s.status}
                            </Label>
                            <Button
                                variant="plain"
                                aria-label="Delete session"
                                onClick={(e) => handleDelete(s.id, e)}
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
                    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
                        {templates.map((t) => (
                            <div
                                key={t.templateId}
                                onClick={() => handleTemplateSelect(t)}
                                style={{
                                    border: "1px solid #d2d2d2",
                                    borderRadius: 8,
                                    padding: 16,
                                    cursor: "pointer",
                                }}
                                onMouseOver={(e) =>
                                    (e.currentTarget.style.borderColor = "#0066cc")
                                }
                                onMouseOut={(e) =>
                                    (e.currentTarget.style.borderColor = "#d2d2d2")
                                }
                            >
                                <div style={{ fontWeight: 600, marginBottom: 4 }}>
                                    {t.name}
                                </div>
                                <div style={{ fontSize: 13, color: "#6a6e73" }}>
                                    {t.description}
                                </div>
                            </div>
                        ))}
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
        </PageSection>
    );
}
