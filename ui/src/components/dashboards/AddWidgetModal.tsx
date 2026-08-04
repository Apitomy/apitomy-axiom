import {
    Button,
    Card,
    CardBody,
    CardTitle,
    Gallery,
    GalleryItem,
    Modal,
    ModalBody,
    ModalHeader,
    Title,
} from "@patternfly/react-core";
import { getWidgetsByCategory } from "./widget-registry";

interface AddWidgetModalProps {
    isOpen: boolean;
    onSelect: (widgetType: string) => void;
    onCancel: () => void;
}

/**
 * Modal listing all available widget types grouped by category.
 */
export function AddWidgetModal({ isOpen, onSelect, onCancel }: AddWidgetModalProps) {
    const byCategory = getWidgetsByCategory();

    return (
        <Modal isOpen={isOpen} onClose={onCancel} variant="large">
            <ModalHeader title="Add Widget" />
            <ModalBody>
                {Array.from(byCategory.entries()).map(([category, widgets]) => (
                    <div key={category} style={{ marginBottom: "24px" }}>
                        <Title headingLevel="h3" size="md"
                               style={{ marginBottom: "12px" }}>{category}</Title>
                        <Gallery hasGutter minWidths={{ default: "250px" }}>
                            {widgets.map(widget => (
                                <GalleryItem key={widget.type}>
                                    <Card isSelectable isCompact
                                          onClick={() => onSelect(widget.type)}>
                                        <CardTitle>{widget.name}</CardTitle>
                                        <CardBody>
                                            <p>{widget.description}</p>
                                            <p style={{ marginTop: "8px", fontSize: "0.85em",
                                                        color: "var(--pf-t--global--color--200)" }}>
                                                Default size: {widget.defaultSize.w}×{widget.defaultSize.h}
                                            </p>
                                        </CardBody>
                                    </Card>
                                </GalleryItem>
                            ))}
                        </Gallery>
                    </div>
                ))}
                {byCategory.size === 0 && (
                    <p>No widgets are available.</p>
                )}
                <div style={{ textAlign: "right", marginTop: "16px" }}>
                    <Button variant="link" onClick={onCancel}>Cancel</Button>
                </div>
            </ModalBody>
        </Modal>
    );
}
