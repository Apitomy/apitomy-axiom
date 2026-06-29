import type { ReactNode } from "react";
import {
    Button,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
} from "@patternfly/react-core";

/**
 * Reusable confirmation modal for destructive actions (delete, cancel, etc.).
 */
export function ConfirmDeleteModal({ isOpen, title, children, onConfirm, onCancel, confirmLabel = "Delete" }: {
    isOpen: boolean;
    title: string;
    children: ReactNode;
    onConfirm: () => void;
    onCancel: () => void;
    confirmLabel?: string;
}) {
    return (
        <Modal isOpen={isOpen} onClose={onCancel} variant="small">
            <ModalHeader title={title} />
            <ModalBody>{children}</ModalBody>
            <ModalFooter>
                <Button variant="danger" onClick={onConfirm}>{confirmLabel}</Button>
                <Button variant="link" onClick={onCancel}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
