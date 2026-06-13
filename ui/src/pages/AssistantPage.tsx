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
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    fetchAssistantSessions,
    createAssistantSession,
    deleteAssistantSession,
    type AssistantSessionInfo,
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
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [newName, setNewName] = useState("");
    const createButtonRef = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (isCreateOpen) {
            setTimeout(() => createButtonRef.current?.focus(), 100);
        }
    }, [isCreateOpen]);
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

    const handleCreate = async () => {
        setCreating(true);
        setCreateError("");
        try {
            const session = await createAssistantSession(newName || undefined);
            setIsCreateOpen(false);
            setNewName("");
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
                                onClick={() => { setNewName(generateSessionName()); setIsCreateOpen(true); }}
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
                                onClick={() => { setNewName(generateSessionName()); setIsCreateOpen(true); }}
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
                isOpen={isCreateOpen}
                onClose={() => setIsCreateOpen(false)}
                variant="small"
                aria-label="New assistant session"
            >
                <ModalHeader title="New Assistant Session" />
                <ModalBody>
                    <TextInput
                        value={newName}
                        onChange={(_e, val) => setNewName(val)}
                        placeholder="Session name (optional)"
                        aria-label="Session name"
                    />
                    {createError && (
                        <div style={{ color: "#c9190b", marginTop: 8, fontSize: "13px" }}>
                            {createError}
                        </div>
                    )}
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
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsCreateOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
