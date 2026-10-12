import { useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { Alert, AlertActionLink, Button, Tab, Tabs, TabTitleText, ToggleGroup, ToggleGroupItem } from "@patternfly/react-core";
import { addItem, type CompositionItem, fetchItem, updateComposition } from "../../config/compositions";
import { type AddRequest, CompositionTree } from "./CompositionTree";
import { PublishDialog } from "./PublishDialog";
import { loadPrefs, savePrefs, type WorkbenchPrefs } from "./workbenchPrefs";
import { useWorkbench } from "./useWorkbench";
import { PaneSplitter } from "./PaneSplitter";
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
    const [publishOpen, setPublishOpen] = useState<boolean>(false);

    const composition = wb.composition;

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

    /** Applies a restored workflow revision to the editor state (Task 10 routes this through the editor handle). */
    const onWorkflowRestored = (item: CompositionItem, content: Record<string, unknown>) => {
        wb.setContent(item.id, content, item.contentRevision);
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

    const leftWidth: number = prefs.leftCollapsed ? RAIL : prefs.panes.left;
    const rightWidth: number = prefs.rightCollapsed ? RAIL : prefs.panes.right;
    const setLeft = (w: number) => setPrefs((p) => ({ ...p, panes: { ...p.panes, left: w } }));
    const setRight = (w: number) => setPrefs((p) => ({ ...p, panes: { ...p.panes, right: w } }));

    return (
        <div className="axiom-workbench"
            style={{ gridTemplateColumns: `${leftWidth}px 6px minmax(0, 1fr) 6px ${rightWidth}px` }}>
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
                <Button variant="plain" onClick={() => updatePrefs({ leftCollapsed: true, rightCollapsed: true })}>
                    Maximize canvas
                </Button>
                <Button variant="secondary" onClick={toggleFocus} title="Ctrl+Shift+F">
                    {searchParams.get("focus") === "1" ? "Exit focus" : "Focus"}
                </Button>
                <Button variant="secondary" onClick={breakOut}>Break out</Button>
                <Button variant="primary" onClick={() => setPublishOpen(true)}>Publish…</Button>
            </div>
            {brokenOut && (
                <Alert className="axiom-workbench__notice" isInline variant="info" title="Opened in another window"
                    actionLinks={<AlertActionLink onClick={() => setBrokenOut(false)}>Bring back here</AlertActionLink>} />
            )}
            {wb.sessionError && (
                <Alert className="axiom-workbench__notice" isInline variant="warning"
                    title={`Assistant unavailable: ${wb.sessionError}`}
                    actionLinks={<AlertActionLink onClick={() => void wb.retrySession()}>Retry</AlertActionLink>} />
            )}

            <aside className="axiom-workbench__left">
                <Button variant="plain" size="sm"
                    aria-label={prefs.leftCollapsed ? "Expand left pane" : "Collapse left pane"}
                    onClick={() => updatePrefs({ leftCollapsed: !prefs.leftCollapsed })}>
                    {prefs.leftCollapsed ? "»" : "«"}
                </Button>
                {!prefs.leftCollapsed && composition && (
                    <CompositionTree composition={composition} items={wb.items} problems={wb.problems}
                        lastCompletedTurnId={wb.lastCompletedTurnId}
                        selectedItemId={activeTab === "workflow" ? workflow?.id : Number(activeTab)}
                        onOpen={openItem} onAdd={addToComposition} onRestored={onRestored} />
                )}
            </aside>
            {prefs.leftCollapsed ? <div /> : (
                <PaneSplitter label="Resize left pane" width={prefs.panes.left} direction={1} onResize={setLeft}
                    onResizeEnd={(w) => savePrefs({ panes: { left: w, right: prefs.panes.right } })} />
            )}

            <main className="axiom-workbench__centre">
                <Tabs activeKey={activeTab} onSelect={(_e, key) => setActiveTab(String(key))}
                    onClose={(_e, key) => closeTab(Number(key))} className="axiom-workbench__tabs">
                    {[
                    <Tab key="workflow" eventKey="workflow" title={<TabTitleText>{workflow?.name ?? "Workflow"}</TabTitleText>}
                        isCloseDisabled>
                        <div className="axiom-workbench__placeholder">{workflow?.name}</div>
                    </Tab>,
                    ...openTabs.map((tid) => {
                        const item = wb.items.find((i) => i.id === tid);
                        return (
                            <Tab key={tid} eventKey={String(tid)}
                                title={<TabTitleText>{item?.name ?? `Item ${tid}`}</TabTitleText>}>
                                <div className="axiom-workbench__placeholder">{item?.name}</div>
                            </Tab>
                        );
                    })]}
                </Tabs>
                <div className="axiom-workbench__problems">
                    {Object.values(wb.problems).reduce((n, p) => n + p.length, 0)} problems
                </div>
            </main>

            {prefs.rightCollapsed ? <div /> : (
                <PaneSplitter label="Resize right pane" width={prefs.panes.right} direction={-1} onResize={setRight}
                    onResizeEnd={(w) => savePrefs({ panes: { left: prefs.panes.left, right: w } })} />
            )}
            <aside className="axiom-workbench__right">
                <Button variant="plain" size="sm"
                    aria-label={prefs.rightCollapsed ? "Expand chat pane" : "Collapse chat pane"}
                    onClick={() => updatePrefs({ rightCollapsed: !prefs.rightCollapsed })}>
                    {prefs.rightCollapsed ? "«" : "»"}
                </Button>
                {prefs.rightCollapsed
                    ? aiWorking && <span className="axiom-workbench__pulse" aria-label="AI is working" />
                    : <div className="axiom-workbench__placeholder">Chat</div>}
            </aside>
            {publishOpen && (
                <PublishDialog compositionId={id} items={wb.items} contents={wb.contents} problems={wb.problems}
                    onPublished={() => void wb.refreshItems().catch(console.error)}
                    onClose={() => setPublishOpen(false)} />
            )}
        </div>
    );
}
