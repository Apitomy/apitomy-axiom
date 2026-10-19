import { useCallback, useState } from "react";
import {
    Button,
    Card,
    CardBody,
    CardHeader,
    CardTitle,
    Flex,
    FlexItem,
    Label,
    Tooltip,
} from "@patternfly/react-core";
import CogIcon from "@patternfly/react-icons/dist/esm/icons/cog-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import { ConfirmDeleteModal } from "../ConfirmDeleteModal";
import { WidgetConfigModal } from "./WidgetConfigModal";
import { WidgetErrorBoundary } from "./WidgetErrorBoundary";
import { getWidget, type WidgetProps, type WidgetRegistryEntry } from "./widget-registry";
import { useWidgetRefresh } from "../../hooks/useWidgetRefresh";

/**
 * Small indicator telling the user whether the dashboard labels apply to a widget.
 * Rendered only when the dashboard has labels.
 */
export function WidgetScopeIndicator({ entry, labels }: { entry: WidgetRegistryEntry; labels: string[] }) {
    if (labels.length === 0) return null;
    const filtered = entry.scope === "subject-labels";
    const text = filtered ? "Filtered" : "Global";
    const tip = filtered
        ? `Filtered by dashboard labels (subject labels): ${labels.join(", ")}`
        : "Global: this widget ignores the dashboard labels";
    return (
        <Tooltip content={tip}>
            <Label isCompact variant="outline" color={filtered ? "blue" : "grey"}
                   tabIndex={0} aria-label={tip}>
                {text}
            </Label>
        </Tooltip>
    );
}

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
    const [refreshToken, setRefreshToken] = useState(0);
    const entry = getWidget(widgetType);
    const bump = useCallback(() => setRefreshToken((t) => t + 1), []);
    useWidgetRefresh(entry?.liveEvents, bump);

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
                  style={isEditing ? { borderBottomRightRadius: 0 } : undefined}>
                <CardHeader>
                    <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                          alignItems={{ default: "alignItemsCenter" }}
                          style={{ width: "100%" }}>
                        <FlexItem>
                            <Flex alignItems={{ default: "alignItemsCenter" }} gap={{ default: "gapSm" }}>
                                <FlexItem><CardTitle>{entry.name}</CardTitle></FlexItem>
                                <FlexItem><WidgetScopeIndicator entry={entry} labels={labels} /></FlexItem>
                            </Flex>
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
                                         onConfigChange={onConfigChange}
                                         refreshToken={refreshToken} />
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
