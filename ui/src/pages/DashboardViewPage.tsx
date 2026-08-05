import { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { Responsive, WidthProvider } from "react-grid-layout";
import type { Layout } from "react-grid-layout";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    PageSection,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { ColoredLabel } from "../components/ColoredLabel";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Dashboard,
    type DashboardWidget,
    type NewDashboard,
    fetchDashboard,
    updateDashboard,
    deleteDashboard,
} from "../config/api";
import { WidgetCard } from "../components/dashboards/WidgetCard";
import { AddWidgetModal } from "../components/dashboards/AddWidgetModal";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { LabelInput } from "../components/LabelInput";
import { getWidget, getDefaultConfig } from "../components/dashboards/widget-registry";

import "../components/dashboards/widgets";

import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";
import "./DashboardViewPage.css";

const ResponsiveGridLayout = WidthProvider(Responsive);

export function DashboardViewPage() {
    const { dashboardId } = useParams<{ dashboardId: string }>();
    const navigate = useNavigate();

    const [dashboard, setDashboard] = useState<Dashboard | null>(null);
    const [loading, setLoading] = useState(true);
    const [isEditing, setIsEditing] = useState(false);

    const [editName, setEditName] = useState("");
    const [editDescription, setEditDescription] = useState("");
    const [editLabels, setEditLabels] = useState<string[]>([]);
    const [editWidgets, setEditWidgets] = useState<DashboardWidget[]>([]);

    const [addWidgetOpen, setAddWidgetOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);

    const load = useCallback(() => {
        if (!dashboardId) return;
        setLoading(true);
        fetchDashboard(Number(dashboardId))
            .then(setDashboard)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [dashboardId]);

    useEffect(() => { load(); }, [load]);

    const enterEditMode = () => {
        if (!dashboard) return;
        setEditName(dashboard.name);
        setEditDescription(dashboard.description ?? "");
        setEditLabels([...dashboard.labels]);
        setEditWidgets(structuredClone(dashboard.widgets));
        setIsEditing(true);
    };

    const cancelEdit = () => {
        setIsEditing(false);
    };

    const handleSave = () => {
        if (!dashboard) return;
        const data: NewDashboard = {
            name: editName,
            description: editDescription || undefined,
            labels: editLabels,
            isDefault: dashboard.isDefault,
            widgets: editWidgets,
        };
        updateDashboard(dashboard.id, data)
            .then((updated) => {
                setDashboard(updated);
                setIsEditing(false);
            })
            .catch(console.error);
    };

    const handleDeleteDashboard = () => {
        if (!dashboard) return;
        deleteDashboard(dashboard.id)
            .then(() => navigate("/dashboards"))
            .catch(console.error);
    };

    const handleAddWidget = (widgetType: string) => {
        const entry = getWidget(widgetType);
        if (!entry) return;
        const newWidget: DashboardWidget = {
            id: crypto.randomUUID(),
            type: widgetType,
            config: getDefaultConfig(widgetType),
            layout: {
                x: 0,
                y: 9999,
                w: entry.defaultSize.w,
                h: entry.defaultSize.h,
            },
        };
        setEditWidgets(prev => [...prev, newWidget]);
        setAddWidgetOpen(false);
    };

    const handleRemoveWidget = (widgetId: string) => {
        setEditWidgets(prev => prev.filter(w => w.id !== widgetId));
    };

    const handleWidgetConfigChange = (widgetId: string, config: Record<string, unknown>) => {
        setEditWidgets(prev => prev.map(w =>
            w.id === widgetId ? { ...w, config } : w
        ));
    };

    const onGridLayoutChange = (layout: Layout[]) => {
        setEditWidgets(prev => prev.map(w => {
            const item = layout.find(l => l.i === w.id);
            if (!item) return w;
            return {
                ...w,
                layout: { x: item.x, y: item.y, w: item.w, h: item.h },
            };
        }));
    };

    const activeWidgets = isEditing ? editWidgets : (dashboard?.widgets ?? []);
    const activeLabels = isEditing ? editLabels : (dashboard?.labels ?? []);

    const gridItems: Layout[] = activeWidgets.map(w => {
        const entry = getWidget(w.type);
        return {
            i: w.id,
            x: w.layout.x,
            y: w.layout.y,
            w: w.layout.w,
            h: w.layout.h,
            minW: entry?.minSize?.w,
            minH: entry?.minSize?.h,
        };
    });

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!dashboard) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Dashboard not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/dashboards">Dashboards</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{dashboard.name}</BreadcrumbItem>
            </Breadcrumb>
            {/* Top bar */}
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                  alignItems={{ default: "alignItemsCenter" }}
                  style={{ marginBottom: "16px" }}>
                <FlexItem>
                    {isEditing ? (
                        <Flex alignItems={{ default: "alignItemsCenter" }}
                              gap={{ default: "gapMd" }}>
                            <FlexItem>
                                <TextInput value={editName}
                                           onChange={(_e, v) => setEditName(v)}
                                           aria-label="Dashboard name"
                                           style={{ fontSize: "1.25rem", fontWeight: 700 }} />
                            </FlexItem>
                        </Flex>
                    ) : (
                        <Flex alignItems={{ default: "alignItemsCenter" }}
                              gap={{ default: "gapMd" }}>
                            <FlexItem>
                                <Title headingLevel="h1" size="lg">{dashboard.name}</Title>
                            </FlexItem>
                            <FlexItem>
                                {dashboard.labels.map(l => (
                                    <ColoredLabel key={l} isCompact
                                               style={{ marginRight: "4px" }}>{l}</ColoredLabel>
                                ))}
                            </FlexItem>
                        </Flex>
                    )}
                </FlexItem>
                <FlexItem>
                    <Flex gap={{ default: "gapSm" }}>
                        {isEditing ? (
                            <>
                                <FlexItem>
                                    <Button variant="primary" icon={<PlusCircleIcon />}
                                            onClick={() => setAddWidgetOpen(true)}>
                                        Add Widget
                                    </Button>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="danger" icon={<TrashIcon />}
                                            onClick={() => setDeleteOpen(true)}>
                                        Delete
                                    </Button>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="primary" icon={<SaveIcon />}
                                            onClick={handleSave}>
                                        Save
                                    </Button>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="link" icon={<TimesIcon />}
                                            onClick={cancelEdit}>
                                        Cancel
                                    </Button>
                                </FlexItem>
                            </>
                        ) : (
                            <FlexItem>
                                <Button variant="secondary" icon={<PencilAltIcon />}
                                        onClick={enterEditMode}>
                                    Edit
                                </Button>
                            </FlexItem>
                        )}
                    </Flex>
                </FlexItem>
            </Flex>

            {/* Edit mode: description and labels */}
            {isEditing && (
                <div style={{ marginBottom: "16px" }}>
                    <Form isHorizontal>
                        <FormGroup label="Description" fieldId="edit-description">
                            <TextArea id="edit-description" value={editDescription}
                                      onChange={(_e, v) => setEditDescription(v)}
                                      rows={2} />
                        </FormGroup>
                        <FormGroup label="Labels" fieldId="edit-labels">
                            <LabelInput labels={editLabels}
                                onChange={(labels) => setEditLabels(labels)} />
                        </FormGroup>
                    </Form>
                </div>
            )}


            {/* Widget grid */}
            {activeWidgets.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        {isEditing
                            ? "Click \"Add Widget\" to add your first widget."
                            : "This dashboard has no widgets. Click \"Edit\" to add some."}
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <ResponsiveGridLayout
                    className="layout"
                    layouts={{ lg: gridItems }}
                    breakpoints={{ lg: 1200, md: 996, sm: 768 }}
                    cols={{ lg: 12, md: 8, sm: 4 }}
                    rowHeight={80}
                    isDraggable={isEditing}
                    isResizable={isEditing}
                    onLayoutChange={isEditing ? onGridLayoutChange : undefined}
                    draggableHandle=".pf-v6-c-card__header"
                    draggableCancel=".pf-v6-c-button"
                >
                    {activeWidgets.map(w => (
                        <div key={w.id}>
                            <WidgetCard
                                widgetType={w.type}
                                config={w.config}
                                labels={activeLabels}
                                isEditing={isEditing}
                                onConfigChange={(config) =>
                                    handleWidgetConfigChange(w.id, config)}
                                onRemove={() => handleRemoveWidget(w.id)}
                            />
                        </div>
                    ))}
                </ResponsiveGridLayout>
            )}

            <AddWidgetModal
                isOpen={addWidgetOpen}
                onSelect={handleAddWidget}
                onCancel={() => setAddWidgetOpen(false)}
            />

            <ConfirmDeleteModal
                isOpen={deleteOpen}
                title="Delete Dashboard"
                onConfirm={handleDeleteDashboard}
                onCancel={() => setDeleteOpen(false)}
            >
                Permanently delete <strong>{dashboard.name}</strong> and all its widgets?
            </ConfirmDeleteModal>
        </PageSection>
    );
}
