import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { Responsive } from "react-grid-layout";
import type { Layout, LayoutItem } from "react-grid-layout";
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
    Label,
    PageSection,
    Tab,
    Tabs,
    TabTitleText,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { ColoredLabel } from "../components/ColoredLabel";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import StarIcon from "@patternfly/react-icons/dist/esm/icons/star-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Dashboard,
    type DashboardTab,
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

/**
 * Measures the width of a container element via a callback ref and a
 * {@link ResizeObserver}, returning the ref to attach and the measured width
 * (null until the element has been measured at least once).
 *
 * <p>This is used in place of react-grid-layout's {@code useContainerWidth}
 * hook, whose measurement/observer setup runs in a one-shot mount effect and
 * therefore requires the observed node to exist on the very first render. On
 * this page the grid container only mounts <em>after</em> the dashboard has
 * loaded asynchronously, so that effect bailed out (the ref was still null),
 * never attached its observer, and left the grid pinned to the library's
 * default 1280px width. A callback ref re-runs whenever the node mounts or
 * unmounts, so it measures correctly regardless of render timing and keeps the
 * grid reflowing on window/container resize.</p>
 *
 * @returns the callback ref to attach to the container and its measured width
 */
function useMeasuredContainerWidth(): {
    containerRef: (node: HTMLDivElement | null) => void;
    width: number | null;
} {
    const [width, setWidth] = useState<number | null>(null);
    const observerRef = useRef<ResizeObserver | null>(null);
    const containerRef = useCallback((node: HTMLDivElement | null): void => {
        observerRef.current?.disconnect();
        observerRef.current = null;
        if (node) {
            const measure = (): void => setWidth(node.getBoundingClientRect().width);
            measure();
            const observer = new ResizeObserver(() => measure());
            observer.observe(node);
            observerRef.current = observer;
        }
    }, []);
    return { containerRef, width };
}

export function DashboardViewPage() {
    const { dashboardId } = useParams<{ dashboardId: string }>();
    const navigate = useNavigate();

    // v2 react-grid-layout requires an explicit `width` prop. We measure the
    // grid container ourselves (see useMeasuredContainerWidth) because the grid
    // mounts only after the dashboard loads.
    const { containerRef, width } = useMeasuredContainerWidth();

    const [dashboard, setDashboard] = useState<Dashboard | null>(null);
    const [loading, setLoading] = useState(true);
    const [isEditing, setIsEditing] = useState(false);

    const [editName, setEditName] = useState("");
    const [editDescription, setEditDescription] = useState("");
    const [editLabels, setEditLabels] = useState<string[]>([]);
    const [editTabs, setEditTabs] = useState<DashboardTab[]>([]);

    const [activeTabId, setActiveTabId] = useState<string>("");
    const [renamingTabId, setRenamingTabId] = useState<string | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const renameInputRef = useRef<HTMLInputElement>(null);

    const [addWidgetOpen, setAddWidgetOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [deleteTabTarget, setDeleteTabTarget] = useState<string | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);

    const load = useCallback(() => {
        if (!dashboardId) return;
        setLoading(true);
        fetchDashboard(Number(dashboardId))
            .then((d) => {
                setDashboard(d);
                if (d.tabs.length > 0 && !activeTabId) {
                    setActiveTabId(d.tabs[0].id);
                }
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [dashboardId]);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        if (renamingTabId && renameInputRef.current) {
            renameInputRef.current.focus();
            renameInputRef.current.select();
        }
    }, [renamingTabId]);

    const activeTabs = isEditing ? editTabs : (dashboard?.tabs ?? []);
    const activeTab = activeTabs.find(t => t.id === activeTabId);
    const activeWidgets = activeTab?.widgets ?? [];
    const activeLabels = isEditing ? editLabels : (dashboard?.labels ?? []);
    const showTabBar = activeTabs.length > 1 || isEditing;

    const enterEditMode = () => {
        if (!dashboard) return;
        setEditName(dashboard.name);
        setEditDescription(dashboard.description ?? "");
        setEditLabels([...dashboard.labels]);
        setEditTabs(structuredClone(dashboard.tabs));
        if (dashboard.tabs.length > 0) {
            setActiveTabId(dashboard.tabs[0].id);
        }
        setIsEditing(true);
    };

    const cancelEdit = () => {
        setIsEditing(false);
        setRenamingTabId(null);
        if (dashboard && dashboard.tabs.length > 0) {
            setActiveTabId(dashboard.tabs[0].id);
        }
    };

    const handleSave = () => {
        if (!dashboard) return;
        const data: NewDashboard = {
            name: editName,
            description: editDescription || undefined,
            labels: editLabels,
            isDefault: dashboard.isDefault,
            tabs: editTabs,
        };
        updateDashboard(dashboard.id, data)
            .then((updated) => {
                setDashboard(updated);
                setIsEditing(false);
                setRenamingTabId(null);
                if (updated.tabs.length > 0) {
                    const stillExists = updated.tabs.find(t => t.id === activeTabId);
                    if (!stillExists) {
                        setActiveTabId(updated.tabs[0].id);
                    }
                }
            })
            .catch(console.error);
    };

    const handleDeleteDashboard = () => {
        if (!dashboard) return;
        deleteDashboard(dashboard.id)
            .then(() => navigate("/dashboards"))
            .catch(console.error);
    };

    const handleSetDefault = () => {
        if (!dashboard || dashboard.isDefault) return;
        const data: NewDashboard = {
            name: dashboard.name,
            description: dashboard.description,
            labels: dashboard.labels,
            isDefault: true,
            tabs: dashboard.tabs,
        };
        updateDashboard(dashboard.id, data)
            .then(setDashboard)
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
        setEditTabs(prev => prev.map(t =>
            t.id === activeTabId
                ? { ...t, widgets: [...t.widgets, newWidget] }
                : t
        ));
        setAddWidgetOpen(false);
    };

    const handleRemoveWidget = (widgetId: string) => {
        setEditTabs(prev => prev.map(t =>
            t.id === activeTabId
                ? { ...t, widgets: t.widgets.filter(w => w.id !== widgetId) }
                : t
        ));
    };

    const handleWidgetConfigChange = (widgetId: string, config: Record<string, unknown>) => {
        setEditTabs(prev => prev.map(t =>
            t.id === activeTabId
                ? { ...t, widgets: t.widgets.map(w => w.id === widgetId ? { ...w, config } : w) }
                : t
        ));
    };

    const onGridLayoutChange = (layout: Layout) => {
        setEditTabs(prev => prev.map(t => {
            if (t.id !== activeTabId) return t;
            return {
                ...t,
                widgets: t.widgets.map(w => {
                    const item = layout.find(l => l.i === w.id);
                    if (!item) return w;
                    return {
                        ...w,
                        layout: { x: item.x, y: item.y, w: item.w, h: item.h },
                    };
                }),
            };
        }));
    };

    // Tab management (edit mode)
    const handleAddTab = () => {
        const newTab: DashboardTab = {
            id: crypto.randomUUID(),
            name: "New Tab",
            widgets: [],
        };
        setEditTabs(prev => [...prev, newTab]);
        setActiveTabId(newTab.id);
    };

    const handleDeleteTab = (tabId: string) => {
        const tab = editTabs.find(t => t.id === tabId);
        if (tab && tab.widgets.length > 0) {
            setDeleteTabTarget(tabId);
            return;
        }
        removeTab(tabId);
    };

    const removeTab = (tabId: string) => {
        setEditTabs(prev => {
            const updated = prev.filter(t => t.id !== tabId);
            if (activeTabId === tabId && updated.length > 0) {
                setActiveTabId(updated[0].id);
            }
            return updated;
        });
        setDeleteTabTarget(null);
    };

    const startRenameTab = (tabId: string) => {
        const tab = editTabs.find(t => t.id === tabId);
        if (!tab) return;
        setRenamingTabId(tabId);
        setRenameValue(tab.name);
    };

    const commitRename = () => {
        if (!renamingTabId) return;
        const trimmed = renameValue.trim();
        if (trimmed) {
            setEditTabs(prev => prev.map(t =>
                t.id === renamingTabId ? { ...t, name: trimmed } : t
            ));
        }
        setRenamingTabId(null);
    };

    const gridItems: LayoutItem[] = activeWidgets.map(w => {
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
                            {dashboard.isDefault && (
                                <FlexItem>
                                    <Label isCompact color="blue">Default</Label>
                                </FlexItem>
                            )}
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
                            <>
                                <FlexItem>
                                    <Button variant="plain"
                                            aria-label="Refresh"
                                            onClick={() => setRefreshKey(k => k + 1)}>
                                        <SyncAltIcon />
                                    </Button>
                                </FlexItem>
                                {!dashboard.isDefault && (
                                    <FlexItem>
                                        <Button variant="secondary" icon={<StarIcon />}
                                                onClick={handleSetDefault}>
                                            Set as Default
                                        </Button>
                                    </FlexItem>
                                )}
                                <FlexItem>
                                    <Button variant="secondary" icon={<PencilAltIcon />}
                                            onClick={enterEditMode}>
                                        Edit
                                    </Button>
                                </FlexItem>
                            </>
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

            {/* Tab bar */}
            {showTabBar && (
                <Flex alignItems={{ default: "alignItemsCenter" }}
                      style={{ marginBottom: "16px" }}>
                    <FlexItem grow={{ default: "grow" }}>
                        <Tabs activeKey={activeTabId}
                              onSelect={(_e, tabId) => setActiveTabId(String(tabId))}>
                            {activeTabs.map(tab => (
                                <Tab key={tab.id} eventKey={tab.id}
                                     title={
                                         <Flex alignItems={{ default: "alignItemsCenter" }}
                                               gap={{ default: "gapSm" }}
                                               flexWrap={{ default: "nowrap" }}>
                                             <FlexItem>
                                                 {isEditing && renamingTabId === tab.id ? (
                                                     <input
                                                         ref={renamingTabId === tab.id ? renameInputRef : undefined}
                                                         value={renameValue}
                                                         onChange={(e) => setRenameValue(e.target.value)}
                                                         onBlur={commitRename}
                                                         onKeyDown={(e) => {
                                                             if (e.key === "Enter") commitRename();
                                                             if (e.key === "Escape") setRenamingTabId(null);
                                                         }}
                                                         style={{
                                                             border: "1px solid var(--pf-t--global--border--color--default)",
                                                             borderRadius: "3px",
                                                             padding: "2px 6px",
                                                             fontSize: "inherit",
                                                             width: `${Math.max(renameValue.length, 4) + 2}ch`,
                                                         }}
                                                         onClick={(e) => e.stopPropagation()}
                                                     />
                                                 ) : (
                                                     <TabTitleText>
                                                         <span onDoubleClick={(e) => {
                                                             if (isEditing) {
                                                                 e.stopPropagation();
                                                                 startRenameTab(tab.id);
                                                             }
                                                         }}>
                                                             {tab.name}
                                                         </span>
                                                     </TabTitleText>
                                                 )}
                                             </FlexItem>
                                             {isEditing && activeTabs.length > 1 && (
                                                 <FlexItem>
                                                     <Button variant="plain" size="sm"
                                                             aria-label={`Delete tab ${tab.name}`}
                                                             style={{ padding: 0 }}
                                                             onClick={(e) => {
                                                                 e.stopPropagation();
                                                                 handleDeleteTab(tab.id);
                                                             }}>
                                                         <TimesIcon />
                                                     </Button>
                                                 </FlexItem>
                                             )}
                                         </Flex>
                                     }
                                />
                            ))}
                        </Tabs>
                    </FlexItem>
                    {isEditing && (
                        <FlexItem>
                            <Button variant="link" icon={<PlusCircleIcon />}
                                    onClick={handleAddTab}>
                                Add Tab
                            </Button>
                        </FlexItem>
                    )}
                </Flex>
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
                <div ref={containerRef}>
                    {width !== null && (
                        <Responsive
                            key={activeTabId}
                            className="layout"
                            width={width}
                            layouts={{ lg: gridItems }}
                            breakpoints={{ lg: 1200, md: 996, sm: 768 }}
                            cols={{ lg: 12, md: 8, sm: 4 }}
                            rowHeight={80}
                            dragConfig={{
                                enabled: isEditing,
                                handle: ".pf-v6-c-card__header",
                                cancel: ".pf-v6-c-button",
                            }}
                            resizeConfig={{ enabled: isEditing }}
                            onLayoutChange={isEditing ? onGridLayoutChange : undefined}
                        >
                            {activeWidgets.map(w => (
                                <div key={w.id}>
                                    <WidgetCard
                                        key={refreshKey}
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
                        </Responsive>
                    )}
                </div>
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

            <ConfirmDeleteModal
                isOpen={deleteTabTarget !== null}
                title="Delete Tab"
                onConfirm={() => deleteTabTarget && removeTab(deleteTabTarget)}
                onCancel={() => setDeleteTabTarget(null)}
            >
                This tab contains widgets. Delete <strong>
                    {editTabs.find(t => t.id === deleteTabTarget)?.name}
                </strong> and all its widgets?
            </ConfirmDeleteModal>
        </PageSection>
    );
}
