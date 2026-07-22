import { useState, useEffect } from "react";
import {
    Alert,
    Button,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
} from "@patternfly/react-core";
import { LabelInput } from "./LabelInput";

/**
 * Modal dialog for editing a list of labels. Opens with the current labels
 * pre-populated. User adds/removes labels, then clicks Save to commit.
 * The onSave callback can return a Promise — the modal waits for it to
 * resolve before closing and shows an error if it rejects.
 */
export function EditLabelsModal({ isOpen, labels, onSave, onClose }: {
    isOpen: boolean;
    labels: string[];
    onSave: (labels: string[]) => void | Promise<void>;
    onClose: () => void;
}) {
    const [draft, setDraft] = useState<string[]>([]);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (isOpen) {
            setDraft([...labels]);
            setError(null);
        }
    }, [isOpen, labels]);

    const handleSave = async () => {
        setSaving(true);
        setError(null);
        try {
            await onSave(draft);
            onClose();
        } catch (e) {
            setError(e instanceof Error ? e.message : "Failed to save labels");
        } finally {
            setSaving(false);
        }
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="small"
            aria-label="Edit Labels">
            <ModalHeader title="Edit Labels" />
            <ModalBody>
                <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                    Type a label and press Enter to add it. Click the X on a label to remove it.
                </p>
                <LabelInput labels={draft} onChange={setDraft} />
                {error && (
                    <Alert variant="danger" title="Save failed" isInline
                        style={{ marginTop: "16px" }}>
                        {error}
                    </Alert>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={handleSave}
                    isLoading={saving} isDisabled={saving}>
                    {saving ? "Saving..." : "Save"}
                </Button>
                <Button variant="link" onClick={onClose} isDisabled={saving}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
