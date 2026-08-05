import { useState } from "react";
import {
    Button,
    Card,
    CardBody,
    CardHeader,
    CardTitle,
    Flex,
    FlexItem,
} from "@patternfly/react-core";
import CogIcon from "@patternfly/react-icons/dist/esm/icons/cog-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import { ConfirmDeleteModal } from "../ConfirmDeleteModal";
import { WidgetConfigModal } from "./WidgetConfigModal";
import { WidgetErrorBoundary } from "./WidgetErrorBoundary";
import { getWidget, type WidgetProps } from "./widget-registry";

interface WidgetCardProps {
    widgetType: string;
    config: Record<string, unknown>;
    labels: string[];
    isEditing: boolean;
    onConfigChange: (config: Record<string, unknown>) => void;
    onRemove: () => void;
}

/**
 * Wraps a widget component in a PatternFly Card with edit-mode controls.
 */
export function WidgetCard({ widgetType, config, labels, isEditing, onConfigChange, onRemove }: WidgetCardProps) {
    const [configOpen, setConfigOpen] = useState(false);
    const [removeOpen, setRemoveOpen] = useState(false);
    const entry = getWidget(widgetType);

    if (!entry) {
        return (
            <Card isFullHeight>
                <CardHeader><CardTitle>Unknown Widget: {widgetType}</CardTitle></CardHeader>
                <CardBody>This widget type is not registered.</CardBody>
            </Card>
        );
    }

    const WidgetComponent = entry.component as React.ComponentType<WidgetProps>;

    return (
        <>
            <Card isFullHeight
                  style={isEditing ? { border: "1px dashed var(--pf-t--global--border--color--default)", borderBottomRightRadius: 0 } : undefined}>
                <CardHeader>
                    <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                          alignItems={{ default: "alignItemsCenter" }}
                          style={{ width: "100%" }}>
                        <FlexItem>
                            <CardTitle>{entry.name}</CardTitle>
                        </FlexItem>
                        {isEditing && (
                            <FlexItem>
                                <Flex gap={{ default: "gapXs" }}>
                                    {entry.configSchema && entry.configSchema.length > 0 && (
                                        <FlexItem>
                                            <Button variant="plain" size="sm"
                                                    onClick={() => setConfigOpen(true)}>
                                                <CogIcon />
                                            </Button>
                                        </FlexItem>
                                    )}
                                    <FlexItem>
                                        <Button variant="plain" size="sm"
                                                onClick={() => setRemoveOpen(true)}>
                                            <TimesIcon />
                                        </Button>
                                    </FlexItem>
                                </Flex>
                            </FlexItem>
                        )}
                    </Flex>
                </CardHeader>
                <CardBody isFilled>
                    <WidgetErrorBoundary>
                        <WidgetComponent config={config} labels={labels}
                                         onConfigChange={onConfigChange} />
                    </WidgetErrorBoundary>
                </CardBody>
            </Card>

            {entry.configSchema && (
                <WidgetConfigModal
                    isOpen={configOpen}
                    widgetName={entry.name}
                    configSchema={entry.configSchema}
                    config={config}
                    onSave={(newConfig) => { onConfigChange(newConfig); setConfigOpen(false); }}
                    onCancel={() => setConfigOpen(false)}
                />
            )}

            <ConfirmDeleteModal
                isOpen={removeOpen}
                title="Remove Widget"
                onConfirm={() => { onRemove(); setRemoveOpen(false); }}
                onCancel={() => setRemoveOpen(false)}
                confirmLabel="Remove"
            >
                Remove <strong>{entry.name}</strong> from this dashboard?
            </ConfirmDeleteModal>
        </>
    );
}
