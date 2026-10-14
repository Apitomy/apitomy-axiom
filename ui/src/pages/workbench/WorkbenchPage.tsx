import { type MouseEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ActionTypeDescriptor, Workflow } from "@apitomy/flow-ui";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
    Alert, AlertActionLink, Breadcrumb, BreadcrumbItem, Button, Modal, ModalBody, ModalHeader, PageSection, Tab, Tabs, TabTitleText, ToggleGroup,
    ToggleGroupItem, Tooltip, Dropdown, Label, DropdownItem, DropdownList, MenuToggle,
} from "@patternfly/react-core";
import {
    CompressArrowsAltIcon, CompressIcon, ExpandArrowsAltIcon, ExpandIcon, ExternalLinkAltIcon, HistoryIcon, PlusCircleIcon,
} from "@patternfly/react-icons";
import { RevisionList } from "./RevisionList";
import {
    addItem, type CompositionItem, type CompositionSessionInfo, type CompositionTestRun, fetchItem, updateComposition,
} from "../../config/compositions";
import {
    type AddableType, AddItemDialog, type AddRequest, askAiAboutItemPrefill, CompositionTree, RemoveItemDialog,
} from "./CompositionTree";
import { PublishDialog } from "./PublishDialog";
import { loadPrefs, savePrefs, type WorkbenchPrefs } from "./workbenchPrefs";
import { useWorkbench } from "./useWorkbench";
import { PaneSplitter } from "./PaneSplitter";
import { WorkflowTab, type WorkflowTabHandle } from "./WorkflowTab";
import { ItemTab, type ItemTabHandle } from "./ItemTab";
import { itemUsages, mergeCatalog } from "./workbenchLogic";
import { ChatPane } from "./ChatPane";
import { TestOutputStrip } from "./TestOutputStrip";
import { TestRunDialog } from "./TestRunDialog";
import { valuesFromInputs } from "./testRunLogic";
import { findActionTypeIdByName, loadEditorActionTypes } from "../../hooks/useWorkflowEditorSpi";
import "./WorkbenchPage.css";

/**
 * Compact label with the engine and model of the composition's running assistant session; hidden until the
 * session has started.
 */
function SessionBadge({ session }: { session: CompositionSessionInfo | undefined }) {
    if (!session?.engine) {
        return null;
    }
    const engine: string = session.engineLabel || session.engine;
    const model: string = session.model || "default";
    return (
        <Tooltip content={<>Engine: {engine} ({session.engine})<br />Model: {session.model
            ?? "the engine's default model"}</>}>
            <Label isCompact className="axiom-workbench__session-badge">{`${engine} · ${model}`}</Label>
        </Tooltip>
    );
}

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
    const [removingId, setRemovingId] = useState<number | null>(null);
    const [creating, setCreating] = useState<AddableType | null>(null);
    const [publishOpen, setPublishOpen] = useState<boolean>(false);

    const composition = wb.composition;
    const workflowTabRef = useRef<WorkflowTabHandle | null>(null);
    /** Handles of the mounted item tabs, by item id (flushed before publishing). */
    const itemTabRefs = useRef<Map<number, ItemTabHandle>>(new Map());
    const [testing, setTesting] = useState<number | null>(null);
    /** Title of the test error alert. */
    const [testError, setTestError] = useState<{ title: string } | null>(null);
    const testValues = useRef<Map<number, { values: Record<string, string>; subjectId?: number }>>(new Map());
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

    const removeItem = (itemId: number) => {
        setRemovingId(null);
        closeTab(itemId);
        wb.removeItem(itemId).catch(console.error);
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
    /** The active action type or tool, if its content is loaded and it can be tested. */
    const testable: CompositionItem | undefined = activeItem && activeItem.type !== "workflow"
        && wb.contents[activeItem.id] ? activeItem : undefined;

    /** Values (and no subject) to pre-fill the next test dialog with, from an AI suggestion. */
    const suggestedValues = useRef<{ itemId: number; values: Record<string, string> } | null>(null);

    /** Flushes the active item's pending save, then opens the test dialog for it. */
    const openTest = async () => {
        if (!testable) {
            return;
        }
        await itemTabRefs.current.get(testable.id)?.flush();
        suggestedValues.current = null;
        setTesting(testable.id);
    };

    /** Opens the test dialog for any action type or tool (e.g. from its row menu), loading it if needed. */
    const openTestFor = async (itemId: number) => {
        setTestError(null);
        try {
            if (!wb.contents[itemId]) {
                await wb.loadContent(itemId);
            }
            await itemTabRefs.current.get(itemId)?.flush();
        } catch (e: unknown) {
            setTestError({ title: `Could not run the test: ${e instanceof Error ? e.message : String(e)}` });
            return;
        }
        suggestedValues.current = null;
        setTesting(itemId);
    };

    /** Opens the test dialog for an AI suggestion, pre-filled with its inputs. */
    const runSuggestion = async (run: CompositionTestRun) => {
        const item: CompositionItem | undefined = wb.items.find((i) => i.id === run.itemId);
        if (!item) {
            setTestError({ title: "The suggested item no longer exists." });
            return;
        }
        setTestError(null);
        try {
            if (!wb.contents[item.id]) {
                await wb.loadContent(item.id);
            }
            await itemTabRefs.current.get(item.id)?.flush();
        } catch (e: unknown) {
            setTestError({ title: `Could not run the test: ${e instanceof Error ? e.message : String(e)}` });
            return;
        }
        suggestedValues.current = { itemId: item.id, values: valuesFromInputs(run.inputs) };
        setTesting(item.id);
    };

    /** Starts a test run; the strip shows progress via SSE, request failures show inline. */
    const runTest = (itemId: number, inputs: Record<string, unknown>, values: Record<string, string>,
                     subjectId?: number) => {
        testValues.current.set(itemId, { values, subjectId });
        suggestedValues.current = null;
        setTesting(null);
        setTestError(null);
        wb.runTest(itemId, inputs, subjectId).catch((e: unknown) =>
            setTestError({ title: `Could not run the test: ${e instanceof Error ? e.message : String(e)}` }));
    };

    // Expand the strip when a run starts live or the AI suggests one: an unseen "running" or "suggested" run that
    // arrives after the run history has loaded. Runs present until then, including those in the render where the
    // history arrives (wb.testRunsLoaded turns true together with the history), are only marked seen.
    const seenRunIds = useRef<Set<string>>(new Set<string>());
    const seenForId = useRef<number>(id);
    const historyLoaded = useRef<boolean>(false);
    useEffect(() => {
        if (seenForId.current !== id) {
            seenForId.current = id;
            seenRunIds.current = new Set<string>();
        }
        const live: boolean = historyLoaded.current && wb.testRunsLoaded;
        historyLoaded.current = wb.testRunsLoaded;
        let started: boolean = false;
        for (const run of wb.testRuns) {
            if (!seenRunIds.current.has(run.id)) {
                seenRunIds.current.add(run.id);
                started = started || (live && (run.status === "running" || run.status === "suggested"));
            }
        }
        if (started && prefs.testStripCollapsed) {
            updatePrefs({ testStripCollapsed: false });
        }
    }, [id, wb.testRuns, wb.testRunsLoaded, prefs.testStripCollapsed, updatePrefs]);

    const removingItem: CompositionItem | undefined = removingId === null ? undefined
        : wb.items.find((i) => i.id === removingId);

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
                <Breadcrumb className="axiom-workbench__name">
                    <BreadcrumbItem><Link to="/workbench">Workbench</Link></BreadcrumbItem>
                    <BreadcrumbItem isActive>{composition?.name ?? "Loading..."}</BreadcrumbItem>
                </Breadcrumb>
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
                        onOpen={openItem} onRemove={setRemovingId}
                        onTest={(itemId) => void openTestFor(itemId)}
                        onAskAi={(item) => askAi(askAiAboutItemPrefill(item))} />
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
                                title={<TabTitleText>{item?.name ?? `Item ${tid}`}</TabTitleText>}
                                onMouseDown={(e: MouseEvent) => {
                                    // Stop the browser's middle-button autoscroll so the click can close the tab.
                                    if (e.button === 1) {
                                        e.preventDefault();
                                    }
                                }}
                                onAuxClick={(e: MouseEvent) => {
                                    if (e.button === 1) {
                                        e.preventDefault();
                                        closeTab(tid);
                                    }
                                }}>
                                {composition && item && wb.contents[tid] ? (
                                    <ItemTab key={tid} ref={itemTabRef(tid)} compositionId={id} item={item} content={wb.contents[tid]}
                                        contentRevision={wb.revisions[tid]}
                                        composition={composition} problems={wb.problems[tid]} onAskAi={askAi}
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
                {testError && (
                    <Alert isInline variant="danger" title={testError.title}
                        actionLinks={<AlertActionLink onClick={() => setTestError(null)}>Dismiss</AlertActionLink>} />
                )}
                <TestOutputStrip runs={wb.testRuns} collapsed={prefs.testStripCollapsed}
                    onToggle={() => updatePrefs({ testStripCollapsed: !prefs.testStripCollapsed })}
                    onOpenItem={openItem}
                    onRunSuggestion={(run) => void runSuggestion(run)}
                    actions={
                        <Tooltip content={testable ? `Run ${testable.name} with sample inputs`
                            : "Open an action type or tool to test it"}>
                            <span>
                                <Button variant="secondary" size="sm" isDisabled={!testable}
                                    onClick={() => void openTest()}>Test…</Button>
                            </span>
                        </Tooltip>
                    } />
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
                            toolbarStart={<>{chatCollapseButton}<span className="axiom-workbench__pane-title">AI Assistant</span>
                                <SessionBadge session={composition.session} /></>}
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
            {removingItem && (
                <RemoveItemDialog item={removingItem} usages={itemUsages(removingItem, wb.items, wb.contents)}
                    onClose={() => setRemovingId(null)} onConfirm={() => removeItem(removingItem.id)} />
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
            {testing !== null && wb.contents[testing] && (() => {
                const item: CompositionItem | undefined = wb.items.find((i) => i.id === testing);
                const suggestion = suggestedValues.current?.itemId === testing ? suggestedValues.current : null;
                const remembered = suggestion ? { values: suggestion.values, subjectId: undefined }
                    : testValues.current.get(testing);
                return item && (
                    <TestRunDialog itemName={item.name} itemType={item.type === "tool" ? "tool" : "action-type"}
                        content={wb.contents[testing]} initialValues={remembered?.values ?? {}}
                        initialSubjectId={remembered?.subjectId} onClose={() => {
                            suggestedValues.current = null;
                            setTesting(null);
                        }}
                        onRun={(inputs, values, subjectId) => runTest(testing, inputs, values, subjectId)} />
                );
            })()}
            {publishOpen && (
                <PublishDialog compositionId={id} items={wb.items} contents={wb.contents} problems={wb.problems}
                    onPublished={() => void wb.refreshItems().catch(console.error)}
                    onClose={() => setPublishOpen(false)} />
            )}
        </div>
        </PageSection>
    );
}
