import { Button, Modal, ModalBody, ModalFooter, ModalHeader } from "@patternfly/react-core";
import type { LineageEntityType } from "../config/api";
import { LineagePanel } from "./LineagePanel";

interface LineageModalProps {
    entityType: LineageEntityType;
    /** Entity ID; the modal is closed when null. */
    id: string | number | null;
    title: string;
    onClose: () => void;
}

/**
 * Modal showing the lineage of an entity that has no detail page of its own (tasks and scheduled
 * job runs). The lineage is loaded when the modal opens.
 */
export function LineageModal({ entityType, id, title, onClose }: LineageModalProps) {
    return (
        <Modal variant="medium" isOpen={id != null} onClose={onClose} aria-label={title}>
            <ModalHeader title={title} />
            <ModalBody>
                {id != null && <LineagePanel entityType={entityType} id={id} expandable={false} />}
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
