import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ActionTypeDescriptor, Workflow } from "@apitomy/flow-ui";
import { useParams, useSearchParams } from "react-router-dom";
import {
    Alert, AlertActionLink, Button, Modal, ModalBody, ModalHeader, PageSection, Tab, Tabs, TabTitleText, ToggleGroup,
    ToggleGroupItem, Tooltip, Dropdown, DropdownItem, DropdownList, MenuToggle,
} from "@patternfly/react-core";
import {
    CompressArrowsAltIcon, CompressIcon, ExpandArrowsAltIcon, ExpandIcon, ExternalLinkAltIcon, HistoryIcon, PlusCircleIcon,
} from "@patternfly/react-icons";
import { RevisionList } from "./RevisionList";
import { addItem, type CompositionItem, fetchItem, updateComposition } from "../../config/compositions";
import { type AddableType, AddItemDialog, type AddRequest, CompositionTree } from "./CompositionTree";
import { PublishDialog } from "./PublishDialog";
import { loadPrefs, savePrefs, type WorkbenchPrefs } from "./workbenchPrefs";
import { useWorkbench } from "./useWorkbench";
import { PaneSplitter } from "./PaneSplitter";
import { WorkflowTab, type WorkflowTabHandle } from "./WorkflowTab";
import { ItemTab, type ItemTabHandle } from "./ItemTab";
import { mergeCatalog } from "./workbenchLogic";
import { ChatPane } from "./ChatPane";
import { findActionTypeIdByName, loadEditorActionTypes } from "../../hooks/useWorkflowEditorSpi";
import "./WorkbenchPage.css";

const RAIL: number = 32;

/**
 * Workbench page for a single composition: toolbar, composition tree, editor tabs, problems and chat.
 */
export function WorkbenchPage() {
    const { compositionId } = useParams<{ compositionId: string }>();
    const id: number = Number(compositionId);
    const wb = useWorkbench(id);
    const [searchParams, setSearchParams] = useSearchParams();
    const [prefs, setPrefs] = useState<WorkbenchPrefs>(() => loadPrefs());
    const [applyMode, setApplyMode] = useState<"live" | "review" | null>(null);
    const [brokenOut, setBrokenOut] = useState<boolean>(false);
    const [openTabs, setOpenTabs] = useState<number[]>([]);
    const [activeTab, setActiveTab] = useState<string>("workflow");
    const [historyOpen, setHistoryOpen] = useState<boolean>(false);
    const [createMenuOpen, setCreateMenuOpen] = useState<boolean>(false);
    const [creating, setCreating] = useState<AddableType | null>(null);
    const [publishOpen, setPublishOpen] = useState<boolean>(false);

    const composition = wb.composition;
    const workflowTabRef = useRef<WorkflowTabHandle | null>(null);
    /** Handles of the mounted item tabs, by item id (flushed before publishing). */
    const itemTabRefs = useRef<Map<number, ItemTabHandle>>(new Map());
    const [preparingPublish, setPreparingPublish] = useState<boolean>(false);
    /** Turn id of the last workflow save made for an AI turn (input to undoPlan). */
    const lastWorkflowSaveTurnId = useRef<string | undefined>(undefined);
    const [prefill, setPrefill] = useState<{ text: string; nonce: number } | undefined>(undefined);
    const [databaseCatalog, setDatabaseCatalog] = useState<ActionTypeDescriptor[]>([]);

    useEffect(() => {
        loadEditorActionTypes().then(setDatabaseCatalog).catch(console.error);
    }, []);

    const draftActionTypes: { name: string; content: Record<string, unknown> }[] = wb.items
        .filter((i) => i.type === "action-type" && wb.contents[i.id])
        .map((i) => ({ name: i.name, content: wb.contents[i.id] }));
    const draftKey: string = JSON.stringify(draftActionTypes);
    const catalog: ActionTypeDescriptor[] = useMemo(() => mergeCatalog(databaseCatalog, draftActionTypes),
        [databaseCatalog, draftKey]);

    useEffect(() => {
        if (applyMode !== null && composition?.applyMode === applyMode) {
            setApplyMode(null);
        }
    }, [applyMode, composition?.applyMode]);
    const mode: "live" | "review" = applyMode ?? composition?.applyMode ?? "live";
    const workflow: CompositionItem | undefined = wb.items.find((i) => i.type === "workflow");
    const aiWorking: boolean = composition?.turnState === "ai-working";

    const updatePrefs = useCallback((partial: Partial<WorkbenchPrefs>) => {
        setPrefs((prev) => ({ ...prev, ...partial }));
        savePrefs(partial);
    }, []);

    const changeMode = (next: "live" | "review") => {
        if (next === mode) {
            return;
        }
        setApplyMode(next);
        updateComposition(id, { applyMode: next }).catch((e) => {
            console.error(e);
            setApplyMode(null);
        });
    };

    const toggleFocus = useCallback(() => {
        setSearchParams((prev) => {
            const next = new URLSearchParams(prev);
            if (next.get("focus") === "1") {
                next.delete("focus");
            } else {
                next.set("focus", "1");
            }
            return next;
        });
    }, [setSearchParams]);

    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "f") {
                e.preventDefault();
                toggleFocus();
            }
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [toggleFocus]);

    const breakOut = () => {
        window.open(`/workbench/${id}?chrome=none`, "_blank", "noopener");
        setBrokenOut(true);
    };

    const closeTab = (itemId: number) => {
        setOpenTabs((prev) => prev.filter((t) => t !== itemId));
        if (activeTab === String(itemId)) {
            setActiveTab("workflow");
        }
    };

    const openItem = (itemId: number) => {
        const item: CompositionItem | undefined = wb.items.find((i) => i.id === itemId);
        if (!item || item.type === "workflow") {
            setActiveTab("workflow");
            return;
        }
        setOpenTabs((prev) => (prev.includes(itemId) ? prev : [...prev, itemId]));
        setActiveTab(String(itemId));
        if (!wb.contents[itemId]) {
            void wb.loadContent(itemId).catch(console.error);
        }
    };

    const openAddedItem = async (item: CompositionItem) => {
        await wb.refreshItems();
        setOpenTabs((prev) => (prev.includes(item.id) ? prev : [...prev, item.id]));
        setActiveTab(String(item.id));
        void wb.loadContent(item.id).catch(console.error);
    };

    const onOpenActionType = (value: string, resolved: boolean) => {
        const draft: CompositionItem | undefined = wb.items.find((i) => i.type === "action-type" && i.name === value);
        if (draft) {
            openItem(draft.id);
            return;
        }
        const add = async () => {
            const existingItemId: number | undefined = resolved ? await findActionTypeIdByName(value) : undefined;
            const created: CompositionItem = await addItem(id, existingItemId !== undefined
                ? { type: "action-type", existingItemId }
                : { type: "action-type", name: value });
            await openAddedItem(created);
        };
        add().catch(console.error);
    };

    const onWorkflowSaved = (saved: CompositionItem, content: Workflow, savedTurnId: string | undefined) => {
        lastWorkflowSaveTurnId.current = savedTurnId;
        wb.setContent(saved.id, content as unknown as Record<string, unknown>, saved.contentRevision);
    };

    const addToComposition = (request: AddRequest) => {
        addItem(id, request)
            .then(async (item: CompositionItem) => {
                await wb.refreshItems();
                setOpenTabs((prev) => (prev.includes(item.id) ? prev : [...prev, item.id]));
                setActiveTab(String(item.id));
                void wb.loadContent(item.id).catch(console.error);
            })
            .catch(console.error);
    };

    /** Applies a restored workflow revision through the editor, which then saves it as the user. */
    const onWorkflowRestored = (_item: CompositionItem, content: Record<string, unknown>) => {
        workflowTabRef.current?.replace(content as unknown as Workflow);
    };

    const onRestored = (item: CompositionItem) => {
        void wb.refreshItems().catch(console.error);
        const apply = (full: CompositionItem) => {
            if (!full.content) {
                return;
            }
            if (full.type === "workflow") {
                onWorkflowRestored(full, full.content);
            } else {
                wb.setContent(full.id, full.content, full.contentRevision);
            }
        };
        if (item.content) {
            apply(item);
        } else {
            fetchItem(id, item.id).then(apply).catch(console.error);
        }
    };

    const activeItemId: number | undefined = activeTab === "workflow" ? workflow?.id : Number(activeTab);
    const activeItem: CompositionItem | undefined = wb.items.find((i) => i.id === activeItemId);

    /** Prefills the chat and reveals a collapsed chat pane. */
    const askAi = (text: string) => {
        setPrefill({ text, nonce: Date.now() });
        if (prefs.rightCollapsed) {
            updatePrefs({ rightCollapsed: false });
        }
    };

    /** Flushes pending workflow saves and returns the current canvas selection. */
    const beforeSend = async (): Promise<{ nodeIds: string[]; edgeIds: string[] }> => {
        const tab: WorkflowTabHandle | null = workflowTabRef.current;
        if (!tab) {
            return { nodeIds: [], edgeIds: [] };
        }
        await tab.flush();
        return tab.selection();
    };

    /** Flushes every open editor's unsaved changes, then opens the Publish dialog. */
    const openPublish = async (): Promise<void> => {
        setPreparingPublish(true);
        try {
            await Promise.all([
                workflowTabRef.current?.flush() ?? Promise.resolve(),
                ...Array.from(itemTabRefs.current.values()).map((h) => h.flush()),
            ]);
        } catch (e) {
            console.error(e);
        } finally {
            setPreparingPublish(false);
        }
        setPublishOpen(true);
    };

    /** Returns a ref callback that tracks the handle of the item tab for `itemId`. */
    const itemTabRef = (itemId: number) => (h: ItemTabHandle | null) => {
        if (h) {
            itemTabRefs.current.set(itemId, h);
        } else {
            itemTabRefs.current.delete(itemId);
        }
    };

    const leftWidth: number = prefs.leftCollapsed ? RAIL : prefs.panes.left;
    const rightWidth: number = prefs.rightCollapsed ? RAIL : prefs.panes.right;
    const setLeft = (w: number) => setPrefs((p) => ({ ...p, panes: { ...p.panes, left: w } }));
    const setRight = (w: number) => setPrefs((p) => ({ ...p, panes: { ...p.panes, right: w } }));

    const focused: boolean = searchParams.get("focus") === "1";
    const maximized: boolean = prefs.leftCollapsed && prefs.rightCollapsed;
    const chatCollapseButton = (
        <Button variant="plain" size="sm"
            aria-label={prefs.rightCollapsed ? "Expand chat pane" : "Collapse chat pane"}
            onClick={() => updatePrefs({ rightCollapsed: !prefs.rightCollapsed })}>
            {prefs.rightCollapsed ? "«" : "»"}
        </Button>
    );

    return (
        <PageSection isFilled padding={{ default: "noPadding" }} hasBodyWrapper={false}
            className="axiom-workbench-page">
        <div className="axiom-workbench"
            style={{ gridTemplateColumns: `${leftWidth}px 0px minmax(0, 1fr) 0px ${rightWidth}px` }}>
            <div className="axiom-workbench__toolbar">
                <strong className="axiom-workbench__name">{composition?.name ?? "Loading..."}</strong>
                <ToggleGroup aria-label="Apply mode">
                    <ToggleGroupItem text="Apply live" buttonId="apply-live" isSelected={mode === "live"}
                        onChange={() => changeMode("live")} />
                    <ToggleGroupItem text="Review changes" buttonId="apply-review" isSelected={mode === "review"}
                        onChange={() => changeMode("review")} />
                </ToggleGroup>
                <ToggleGroup aria-label="AI layout">
                    <ToggleGroupItem text="Place new nodes" buttonId="layout-place"
                        isSelected={prefs.applyLayout === "place"}
                        onChange={() => updatePrefs({ applyLayout: "place" })} />
                    <ToggleGroupItem text="Tidy after AI changes" buttonId="layout-tidy"
                        isSelected={prefs.applyLayout === "tidy"}
                        onChange={() => updatePrefs({ applyLayout: "tidy" })} />
                </ToggleGroup>
                <span className="axiom-workbench__spacer" />
                <Tooltip content={maximized ? "Restore side panels" : "Maximize canvas"}>
                    <Button variant="plain" aria-label={maximized ? "Restore side panels" : "Maximize canvas"}
                        icon={maximized ? <CompressIcon /> : <ExpandIcon />}
                        onClick={() => updatePrefs({ leftCollapsed: !maximized, rightCollapsed: !maximized })} />
                </Tooltip>
                <Tooltip content={`${focused ? "Exit focus" : "Focus"} (Ctrl+Shift+F)`}>
                    <Button variant="plain" aria-label={focused ? "Exit focus" : "Focus"} onClick={toggleFocus}
                        icon={focused ? <CompressArrowsAltIcon /> : <ExpandArrowsAltIcon />} />
                </Tooltip>
                <Tooltip content="Break out into a new window">
                    <Button variant="plain" aria-label="Break out" icon={<ExternalLinkAltIcon />} onClick={breakOut} />
                </Tooltip>
                <Button variant="primary" isDisabled={preparingPublish} isLoading={preparingPublish}
                    onClick={() => void openPublish()}>Publish…</Button>
            </div>
            {brokenOut && (
                <Alert className="axiom-workbench__notice" isInline variant="info" title="Opened in another window"
                    actionLinks={<AlertActionLink onClick={() => setBrokenOut(false)}>Bring back here</AlertActionLink>} />
            )}
            {wb.sessionError && prefs.rightCollapsed && (
                <Alert className="axiom-workbench__notice" isInline variant="warning"
                    title={`Assistant unavailable: ${wb.sessionError}`}
                    actionLinks={<AlertActionLink onClick={() => void wb.retrySession()}>Retry</AlertActionLink>} />
            )}

            <aside className="axiom-workbench__left">
                <div className="axiom-workbench__pane-toolbar">
                    {!prefs.leftCollapsed && <span className="axiom-workbench__pane-title">Components</span>}
                    {!prefs.leftCollapsed && (
                        <Dropdown isOpen={createMenuOpen} onOpenChange={setCreateMenuOpen}
                            popperProps={{ position: "start" }}
                            onSelect={(_e, value) => {
                                setCreateMenuOpen(false);
                                setCreating(value as AddableType);
                            }}
                            toggle={(ref) => (
                                <Tooltip content="Create">
                                    <MenuToggle ref={ref} variant="plain" size="sm" aria-label="Create"
                                        className="axiom-workbench__pane-toolbar-end" icon={<PlusCircleIcon />}
                                        isExpanded={createMenuOpen} onClick={() => setCreateMenuOpen((o) => !o)} />
                                </Tooltip>
                            )}>
                            <DropdownList>
                                <DropdownItem value="action-type">Action Type</DropdownItem>
                                <DropdownItem value="tool">Tool</DropdownItem>
                            </DropdownList>
                        </Dropdown>
                    )}
                    {!prefs.leftCollapsed && (
                        <Tooltip content="Revision history">
                            <Button variant="plain" size="sm" icon={<HistoryIcon />} aria-label="Revision history"
                                isDisabled={!activeItem}
                                onClick={() => setHistoryOpen(true)} />
                        </Tooltip>
                    )}
                    <Button variant="plain" size="sm"
                        className={prefs.leftCollapsed ? "axiom-workbench__pane-toolbar-end" : undefined}
                        aria-label={prefs.leftCollapsed ? "Expand left pane" : "Collapse left pane"}
                        onClick={() => updatePrefs({ leftCollapsed: !prefs.leftCollapsed })}>
                        {prefs.leftCollapsed ? "»" : "«"}
                    </Button>
                </div>
                {!prefs.leftCollapsed && composition && (
                    <CompositionTree composition={composition} items={wb.items} problems={wb.problems}
                        lastCompletedTurnId={wb.lastCompletedTurnId}
                        selectedItemId={activeTab === "workflow" ? workflow?.id : Number(activeTab)}
                        onOpen={openItem} />
                )}
            </aside>
            {prefs.leftCollapsed ? <div className="axiom-workbench__splitter axiom-workbench__splitter--left" /> : (
                <PaneSplitter className="axiom-workbench__splitter--left" label="Resize left pane" width={prefs.panes.left} direction={1} onResize={setLeft}
                    onResizeEnd={(w) => savePrefs({ panes: { left: w, right: prefs.panes.right } })} />
            )}

            <main className="axiom-workbench__centre">
                <Tabs activeKey={activeTab} onSelect={(_e, key) => setActiveTab(String(key))}
                    onClose={(_e, key) => closeTab(Number(key))} className="axiom-workbench__tabs">
                    {[
                    <Tab key="workflow" eventKey="workflow" title={<TabTitleText>{workflow?.name ?? "Workflow"}</TabTitleText>}
                        isCloseDisabled>
                        {composition && workflow && wb.contents[workflow.id] ? (
                            <WorkflowTab key={workflow.id} ref={workflowTabRef} compositionId={id} item={workflow}
                                initialContent={wb.contents[workflow.id] as unknown as Workflow}
                                composition={{ ...composition, applyMode: mode }} catalog={catalog}
                                applyLayout={prefs.applyLayout} pending={wb.pending}
                                onPendingHandled={wb.markPendingHandled} onSaved={onWorkflowSaved}
                                onOpenActionType={onOpenActionType}
                                onAskAi={askAi} />
                        ) : (
                            <div className="axiom-workbench__placeholder">{workflow?.name}</div>
                        )}
                    </Tab>,
                    ...openTabs.map((tid) => {
                        const item = wb.items.find((i) => i.id === tid);
                        return (
                            <Tab key={tid} eventKey={String(tid)}
                                title={<TabTitleText>{item?.name ?? `Item ${tid}`}</TabTitleText>}>
                                {composition && item && wb.contents[tid] ? (
                                    <ItemTab key={tid} ref={itemTabRef(tid)} compositionId={id} item={item} content={wb.contents[tid]}
                                        contentRevision={wb.revisions[tid]}
                                        composition={composition} problems={wb.problems[tid]}
                                        onSaved={(saved, content) => {
                                            wb.setContent(saved.id, content, saved.contentRevision);
                                            void wb.refreshItems().catch(console.error);
                                        }} />
                                ) : (
                                    <div className="axiom-workbench__placeholder">{item?.name}</div>
                                )}
                            </Tab>
                        );
                    })]}
                </Tabs>
            </main>

            {prefs.rightCollapsed ? <div className="axiom-workbench__splitter axiom-workbench__splitter--right" /> : (
                <PaneSplitter className="axiom-workbench__splitter--right" label="Resize right pane" width={prefs.panes.right} direction={-1} onResize={setRight}
                    onResizeEnd={(w) => savePrefs({ panes: { left: prefs.panes.left, right: w } })} />
            )}
            <aside className="axiom-workbench__right">
                {(prefs.rightCollapsed || !composition) && chatCollapseButton}
                {prefs.rightCollapsed
                    ? aiWorking && <span className="axiom-workbench__pulse" aria-label="AI is working" />
                    : composition && (
                        <ChatPane composition={composition} sessionError={wb.sessionError}
                            toolbarStart={<>{chatCollapseButton}<span className="axiom-workbench__pane-title">AI Assistant</span></>}
                            lastCompletedTurnId={wb.lastCompletedTurnId} prefill={prefill}
                            beforeSend={beforeSend} onRetrySession={() => void wb.retrySession()}
                            onReplaceWorkflow={(w) => workflowTabRef.current?.replace(w)}
                            lastWorkflowSaveTurnId={lastWorkflowSaveTurnId.current}
                            beforeUndo={() => workflowTabRef.current?.flush() ?? Promise.resolve()}
                            onUndone={() => void wb.refreshItems().catch(console.error)} />
                    )}
            </aside>
            {creating && (
                <AddItemDialog type={creating} onClose={() => setCreating(null)}
                    onSubmit={(request: AddRequest) => {
                        setCreating(null);
                        addToComposition(request);
                    }} />
            )}
            {historyOpen && activeItem && (
                <Modal isOpen variant="medium" onClose={() => setHistoryOpen(false)} aria-labelledby="revision-history-title">
                    <ModalHeader title={`Revision history: ${activeItem.name}`} labelId="revision-history-title" />
                    <ModalBody>
                        <RevisionList compositionId={id} item={activeItem} onRestored={(item) => {
                            setHistoryOpen(false);
                            onRestored(item);
                        }} />
                    </ModalBody>
                </Modal>
            )}
            {publishOpen && (
                <PublishDialog compositionId={id} items={wb.items} contents={wb.contents} problems={wb.problems}
                    onPublished={() => void wb.refreshItems().catch(console.error)}
                    onClose={() => setPublishOpen(false)} />
            )}
        </div>
        </PageSection>
    );
}
