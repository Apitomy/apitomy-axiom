import { useState, useEffect, useCallback } from "react";
import {
    Label,
    EmptyState,
    EmptyStateBody,
    Spinner,
    Tooltip,
} from "@patternfly/react-core";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import {
    fetchAssistantItems,
    fetchAssistantItemContent,
    type AssistantItem,
} from "../../config/api";
import { ToolDetailModal } from "./ToolDetailModal";
import { ActionTypeDetailModal } from "./ActionTypeDetailModal";
import { ReportDefinitionDetailModal } from "./ReportDefinitionDetailModal";

interface AssistantGeneratedItemsProps {
    sessionId: string;
    refreshTrigger: number;
    onItemCountChanged?: (count: number) => void;
}

const TYPE_LABELS: Record<string, { label: string; color: "blue" | "green" | "purple" }> = {
    "tools": { label: "Tool", color: "blue" },
    "action-types": { label: "Action Type", color: "green" },
    "report-definitions": { label: "Report", color: "purple" },
};

export function AssistantGeneratedItems({ sessionId, refreshTrigger, onItemCountChanged }: AssistantGeneratedItemsProps) {
    const [items, setItems] = useState<AssistantItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [selectedItem, setSelectedItem] = useState<{
        type: string; name: string; validationErrors?: string[];
    } | null>(null);
    const [itemContent, setItemContent] = useState<Record<string, unknown> | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchAssistantItems(sessionId)
            .then((result) => {
                setItems(result);
                onItemCountChanged?.(result.length);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [sessionId, onItemCountChanged]);

    useEffect(() => {
        load();
    }, [load, refreshTrigger]);

    const handleItemClick = async (item: AssistantItem) => {
        try {
            const content = await fetchAssistantItemContent(sessionId, item.type, item.name);
            setItemContent(content);
            setSelectedItem({
                type: item.type, name: item.name,
                validationErrors: item.validationErrors,
            });
        } catch (err) {
            console.error("Failed to load item:", err);
        }
    };

    const closeModal = () => {
        setSelectedItem(null);
        setItemContent(null);
    };

    return (
        <div style={{ padding: "16px", height: "100%", overflow: "auto" }}>
            <div style={{
                fontWeight: 600,
                fontSize: "14px",
                marginBottom: "12px",
                color: "#151515",
            }}>
                Generated Items ({items.length})
            </div>

            {loading && items.length === 0 && (
                <EmptyState variant="sm">
                    <Spinner size="md" />
                    <EmptyStateBody>Loading items...</EmptyStateBody>
                </EmptyState>
            )}

            {!loading && items.length === 0 && (
                <EmptyState variant="sm">
                    <EmptyStateBody>
                        No items generated yet. Ask the assistant to create tools,
                        action types, or report definitions.
                    </EmptyStateBody>
                </EmptyState>
            )}

            {items.map((item) => {
                const typeInfo = TYPE_LABELS[item.type] || { label: item.type, color: "blue" as const };
                return (
                    <div
                        key={`${item.type}/${item.name}`}
                        onClick={() => handleItemClick(item)}
                        style={{
                            display: "flex",
                            alignItems: "center",
                            gap: "8px",
                            padding: "10px 12px",
                            marginBottom: "4px",
                            borderRadius: "6px",
                            cursor: "pointer",
                            backgroundColor: "#fafafa",
                            border: "1px solid #d2d2d2",
                        }}
                        onMouseEnter={(e) => {
                            e.currentTarget.style.backgroundColor = "#f0f0f0";
                        }}
                        onMouseLeave={(e) => {
                            e.currentTarget.style.backgroundColor = "#fafafa";
                        }}
                    >
                        <Label isCompact color={typeInfo.color}>
                            {typeInfo.label}
                        </Label>
                        <span style={{ flex: 1, fontSize: "13px" }}>{item.name}</span>
                        {item.valid ? (
                            <CheckCircleIcon style={{ color: "#3e8635" }} />
                        ) : (
                            <Tooltip content={
                                `${(item.validationErrors || []).length} validation error(s)`
                            }>
                                <ExclamationCircleIcon style={{ color: "#c9190b" }} />
                            </Tooltip>
                        )}
                    </div>
                );
            })}

            {selectedItem?.type === "tools" && itemContent && (
                <ToolDetailModal
                    isOpen
                    onClose={closeModal}
                    name={selectedItem.name}
                    content={itemContent}
                    errors={selectedItem.validationErrors}
                />
            )}
            {selectedItem?.type === "action-types" && itemContent && (
                <ActionTypeDetailModal
                    isOpen
                    onClose={closeModal}
                    name={selectedItem.name}
                    content={itemContent}
                    errors={selectedItem.validationErrors}
                />
            )}
            {selectedItem?.type === "report-definitions" && itemContent && (
                <ReportDefinitionDetailModal
                    isOpen
                    onClose={closeModal}
                    name={selectedItem.name}
                    content={itemContent}
                    errors={selectedItem.validationErrors}
                />
            )}
        </div>
    );
}
