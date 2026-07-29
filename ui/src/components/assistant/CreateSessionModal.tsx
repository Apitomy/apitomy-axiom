import { useState, useEffect, useRef } from "react";
import {
    Alert,
    Button,
    Form,
    FormGroup,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    SearchInput,
    TextInput,
} from "@patternfly/react-core";
import {
    createAssistantSession,
    fetchAssistantTemplates,
    type AssistantSessionInfo,
    type SessionTemplate,
} from "../../config/api";
import "../../pages/AssistantPage.css";

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

interface CreateSessionModalProps {
    isOpen: boolean;
    onClose: () => void;
    onSessionCreated: (session: AssistantSessionInfo) => void;
    projectId?: number;
    defaultName?: string;
}

export function CreateSessionModal({
    isOpen, onClose, onSessionCreated, projectId, defaultName,
}: CreateSessionModalProps) {
    const [templates, setTemplates] = useState<SessionTemplate[]>([]);
    const [templateFilter, setTemplateFilter] = useState("");
    const [selectedTemplate, setSelectedTemplate] = useState<SessionTemplate | null>(null);
    const [isNameModalOpen, setIsNameModalOpen] = useState(false);
    const [newName, setNewName] = useState("");
    const [creating, setCreating] = useState(false);
    const [createError, setCreateError] = useState("");
    const createButtonRef = useRef<HTMLButtonElement>(null);

    useEffect(() => {
        if (isOpen) {
            fetchAssistantTemplates()
                .then(setTemplates)
                .catch(console.error);
            setTemplateFilter("");
        }
    }, [isOpen]);

    useEffect(() => {
        if (isNameModalOpen) {
            setTimeout(() => createButtonRef.current?.focus(), 100);
        }
    }, [isNameModalOpen]);

    const filteredTemplates = templates
        .filter((t) => {
            if (!templateFilter) return true;
            const lower = templateFilter.toLowerCase();
            return t.name.toLowerCase().includes(lower)
                || t.description.toLowerCase().includes(lower);
        })
        .sort((a, b) => a.name.localeCompare(b.name));

    const handleTemplateSelect = (template: SessionTemplate) => {
        setSelectedTemplate(template);
        setNewName(defaultName || generateSessionName());
        setIsNameModalOpen(true);
    };

    const handleCreate = async () => {
        if (!selectedTemplate) return;
        setCreating(true);
        setCreateError("");
        try {
            const session = await createAssistantSession(
                selectedTemplate.templateId, newName || undefined, projectId);
            onSessionCreated(session);
        } catch (err: unknown) {
            const e = err as { message?: string };
            setCreateError(e.message || "Failed to create session");
        } finally {
            setCreating(false);
        }
    };

    const handleClose = () => {
        setIsNameModalOpen(false);
        setSelectedTemplate(null);
        setNewName("");
        setCreateError("");
        onClose();
    };

    return (
        <>
            <Modal
                isOpen={isOpen && !isNameModalOpen}
                onClose={handleClose}
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
                        className="axiom-assistant-page__template-filter"
                    />
                    <div className="axiom-assistant-page__template-list">
                        {filteredTemplates.length === 0 ? (
                            <div className="axiom-assistant-page__template-list__empty">
                                No templates match your filter.
                            </div>
                        ) : (
                            filteredTemplates.map((t) => (
                                <div
                                    key={t.templateId}
                                    className="axiom-assistant-page__template-item"
                                    onClick={() => handleTemplateSelect(t)}
                                >
                                    <div className="axiom-assistant-page__template-item__name">
                                        {t.name}
                                        {t.builtIn && (
                                            <Label className="axiom-assistant-page__template-item__badge"
                                                isCompact>Built-in</Label>
                                        )}
                                    </div>
                                    {t.description && (
                                        <div className="axiom-assistant-page__template-item__desc">
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
                isOpen={isOpen && isNameModalOpen}
                onClose={handleClose}
                variant="small"
                aria-label="Name session"
            >
                <ModalHeader title="Name Your Session" />
                <ModalBody>
                    {createError && (
                        <Alert variant="danger" isInline title={createError}
                            className="axiom-assistant-page__create-error" />
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
                    <Button variant="link" onClick={handleClose}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>
        </>
    );
}
