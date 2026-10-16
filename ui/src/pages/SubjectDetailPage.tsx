import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { markdownMermaidComponents } from "../components/MermaidBlock";
import "../axiom-markdown.css";
import {
    Alert,
    AlertActionCloseButton,
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Card,
    CardBody,
    Content,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Gallery,
    GalleryItem,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Tab,
    TabTitleText,
    Tabs,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import ChatIcon from "@patternfly/react-icons/dist/esm/icons/chat-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import CheckIcon from "@patternfly/react-icons/dist/esm/icons/check-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import RedoIcon from "@patternfly/react-icons/dist/esm/icons/redo-icon";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import GithubIcon from "@patternfly/react-icons/dist/esm/icons/github-icon";
import JiraIcon from "@patternfly/react-icons/dist/esm/icons/jira-icon";
import AngleRightIcon from "@patternfly/react-icons/dist/esm/icons/angle-right-icon";
import AngleDownIcon from "@patternfly/react-icons/dist/esm/icons/angle-down-icon";
import {
    type InboxItem,
    type Subject,
    type SubjectMetrics,
    type StreamEvent,
    type Task,
    type ThreadEntry,
    type WorkflowRunSummary,
    fetchAgents,
    fetchWorkflowRuns,
    rerunWorkflowRun,
    fetchSubject,
    fetchSubjectMetrics,
    fetchSubjectTasks,
    fetchStreamEvents,
    fetchThreadEntries,
    deleteSubject,
    updateSubject,
    updateSubjectBody,
    formatBytes,
    fetchInboxItem,
    completeInboxItem,
} from "../config/api";
import { EditLabelsModal } from "../components/EditLabelsModal";
import { LabelDisplay } from "../components/LabelDisplay";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { LineageModal } from "../components/LineageModal";
import { CreateSessionModal } from "../components/assistant/CreateSessionModal";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { DynamicFormRenderer } from "../components/DynamicFormRenderer";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    InProgress: "green",
    Completed: "grey",
    Pending: "blue",
    AwaitingInput: "orange",
    Failed: "red",
    Cancelled: "grey",
};

const RUNS_TAB = 6;

const RUN_STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    waiting: "orange",
    completed: "green",
    failed: "red",
    cancelled: "grey",
};

/** Shows a subject: header, runs timeline, body, tasks, thread, events, metrics and workflow. */
export function SubjectDetailPage() {
    const { subjectId } = useParams<{ subjectId: string }>();
    const navigate = useNavigate();
    const effectiveTheme = useEffectiveTheme();
    const [subject, setSubject] = useState<Subject | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [thread, setThread] = useState<ThreadEntry[]>([]);
    const [events, setEvents] = useState<StreamEvent[]>([]);
    const [metrics, setMetrics] = useState<SubjectMetrics | null>(null);
    const [agentNames, setAgentNames] = useState<Record<number, string>>({});
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [activeTab, setActiveTab] = useState(RUNS_TAB);
    const [loading, setLoading] = useState(true);
    const [isMetadataExpanded, setIsMetadataExpanded] = useState(false);

    const [isDeleteOpen, setIsDeleteOpen] = useState(false);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);

    // Body edit state. `editBody` holds the initial value passed to the code
    // editor (kept stable while editing so PatternFly's CodeEditor does not
    // re-apply it to Monaco and jump the cursor). The live, in-progress value
    // lives in a ref and is read only when saving.
    const [isEditingBody, setIsEditingBody] = useState(false);
    const [editBody, setEditBody] = useState("");
    const editBodyRef = useRef("");
    const [bodySaveError, setBodySaveError] = useState("");

    const [isAssistantModalOpen, setIsAssistantModalOpen] = useState(false);

    const id = Number(subjectId);

    const cancelBodyEdit = useCallback(() => {
        setIsEditingBody(false);
        setBodySaveError("");
    }, []);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        Promise.all([
            fetchSubject(id),
            fetchSubjectTasks(id),
            fetchThreadEntries(id),
            fetchSubjectMetrics(id),
            fetchAgents(),
            fetchWorkflowRuns(1, 100, { subjectId: id }),
        ])
            .then(([p, t, th, m, agents, r]) => {
                setSubject(p);
                setRuns([...r.items].sort(
                    (a, b) => new Date(b.startedOn).getTime() - new Date(a.startedOn).getTime()));
                setTasks(t);
                setThread(th);
                setMetrics(m);
                const names: Record<number, string> = {};
                agents.forEach((a) => { names[a.id] = a.name; });
                setAgentNames(names);
                // Load stream events by subject ref
                if (p.ref) {
                    fetchStreamEvents(1, 100, undefined, undefined, p.ref)
                        .then(r => setEvents(r.items))
                        .catch(console.error);
                }
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading subject...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    if (!subject) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Subject not found.</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection isFilled className="subject-detail">
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/subjects">Subjects</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{subject.name}</BreadcrumbItem>
            </Breadcrumb>

            {/* Subject header */}
            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {subject.refSource === "github" && <GithubIcon style={{ marginRight: 8 }} />}
                        {subject.refSource === "jira" && <JiraIcon style={{ marginRight: 8 }} />}
                        {subject.type === "issue" && <BugIcon style={{ marginRight: 8 }} />}
                        {subject.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 8 }} />}
                        {subject.name}
                    </Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="plain"
                        aria-label="Refresh"
                        onClick={loadData}
                        style={{ marginRight: "8px" }}
                    >
                        <SyncAltIcon />
                    </Button>
                    <Button
                        variant="secondary"
                        icon={<ChatIcon />}
                        onClick={() => setIsAssistantModalOpen(true)}
                        style={{ marginRight: "8px" }}
                    >
                        Assistant
                    </Button>
                    {!subject.active && (
                        <Button variant="danger" icon={<TrashIcon />}
                            onClick={() => setIsDeleteOpen(true)}
                            style={{ marginRight: "8px" }}>
                            Delete
                        </Button>
                    )}
                    <Label color={subject.active ? "green" : "grey"}>
                        {subject.active ? "Active" : "Inactive"}
                    </Label>
                </FlexItem>
            </Flex>

            {/* Subject metadata — collapsible. Collapsed shows a compact
                summary bar (type, source, labels); expanded shows the full
                description list. Clicking the bar toggles between the two. */}
            <div
                className="subject-detail__metadata-toggle"
                role="button"
                tabIndex={0}
                aria-expanded={isMetadataExpanded}
                aria-label={isMetadataExpanded ? "Collapse subject details" : "Expand subject details"}
                onClick={() => setIsMetadataExpanded((v) => !v)}
                onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        setIsMetadataExpanded((v) => !v);
                    }
                }}
            >
                <Flex
                    alignItems={{ default: "alignItemsCenter" }}
                    spaceItems={{ default: "spaceItemsSm" }}
                    flexWrap={{ default: "nowrap" }}
                >
                    <FlexItem>
                        {isMetadataExpanded ? <AngleDownIcon /> : <AngleRightIcon />}
                    </FlexItem>
                    <FlexItem className="subject-detail__metadata-term">
                        Type:
                    </FlexItem>
                    <FlexItem>
                        <Label isCompact>{subject.type}</Label>
                    </FlexItem>
                    {subject.refSource && (
                        <>
                            <FlexItem className="subject-detail__metadata-term">
                                Source:
                            </FlexItem>
                            <FlexItem>
                                <Label isCompact>{subject.refSource}</Label>
                            </FlexItem>
                        </>
                    )}
                    {subject.ref && (
                        <>
                            <FlexItem className="subject-detail__metadata-term">
                                Ref:
                            </FlexItem>
                            <FlexItem>
                                <code>{subject.ref}</code>
                            </FlexItem>
                        </>
                    )}
                    <FlexItem
                        onClick={(e) => e.stopPropagation()}
                        style={{ marginLeft: "auto" }}
                    >
                        <LabelDisplay labels={subject.labels || []}
                            onEdit={() => setIsLabelsOpen(true)} />
                    </FlexItem>
                </Flex>
            </div>

            {isMetadataExpanded && (
            <DescriptionList isHorizontal isCompact columnModifier={{ default: "3Col" }}
                style={{ marginTop: "8px", marginLeft: "32px" }}>
                <DescriptionListGroup>
                    <DescriptionListTerm>Reference</DescriptionListTerm>
                    <DescriptionListDescription>
                        {subject.ref ? (
                            <a href={subject.ref}
                                target="_blank" rel="noopener noreferrer">
                                {subject.ref}
                            </a>
                        ) : (
                            "—"
                        )}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                {subject.repository && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Repository</DescriptionListTerm>
                        <DescriptionListDescription>
                            {subject.repository}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                )}
                {Object.entries(subject.metadata ?? {}).map(([key, value]) => (
                    <DescriptionListGroup key={key}>
                        <DescriptionListTerm>{key}</DescriptionListTerm>
                        <DescriptionListDescription>{value}</DescriptionListDescription>
                    </DescriptionListGroup>
                ))}
                <DescriptionListGroup>
                    <DescriptionListTerm>Created</DescriptionListTerm>
                    <DescriptionListDescription>
                        {new Date(subject.createdOn).toLocaleString()}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Updated</DescriptionListTerm>
                    <DescriptionListDescription>
                        {new Date(subject.updatedOn).toLocaleString()}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Workspace</DescriptionListTerm>
                    <DescriptionListDescription>
                        <code style={{ fontSize: "13px" }}>
                            ~/.axiom/workspaces/subject-{subject.id}
                        </code>
                    </DescriptionListDescription>
                </DescriptionListGroup>
            </DescriptionList>
            )}

            {/* Tabs fill the remaining vertical space; the active tab's
                content scrolls when it exceeds the available height. */}
            <div className="subject-detail__tabs">
                <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                    <Tab eventKey={RUNS_TAB} title={<TabTitleText>Runs ({runs.length})</TabTitleText>} />
                    <Tab eventKey={0} title={<TabTitleText>Body</TabTitleText>} />
                    <Tab eventKey={1} title={<TabTitleText>Tasks ({tasks.length})</TabTitleText>} />
                    <Tab eventKey={2} title={<TabTitleText>Thread ({thread.length})</TabTitleText>} />
                    <Tab eventKey={3} title={<TabTitleText>Events ({events.length})</TabTitleText>} />
                    <Tab eventKey={4} title={<TabTitleText>Metrics</TabTitleText>} />
                </Tabs>
                <div className="subject-detail__tab-content">
                    {activeTab === 0 && (
                        <div style={{
                            padding: "0 14px",
                            position: "relative",
                            ...(isEditingBody
                                ? { height: "100%", display: "flex", flexDirection: "column" }
                                : {}),
                        }}>
                            <div className={`subject-detail__body-actions${isEditingBody ? " subject-detail__body-actions--offset" : ""}`}>
                                {isEditingBody ? (
                                    <>
                                        <Button variant="plain" aria-label="Save body"
                                            onClick={() => {
                                                setBodySaveError("");
                                                const newBody = editBodyRef.current;
                                                updateSubjectBody(id, newBody)
                                                    .then(() => {
                                                        setSubject({ ...subject, body: newBody });
                                                        setIsEditingBody(false);
                                                    })
                                                    .catch((err) => {
                                                        setBodySaveError(err.message || "Failed to save body");
                                                    });
                                            }}>
                                            <CheckIcon />
                                        </Button>
                                        <Button variant="plain" aria-label="Cancel edit"
                                            onClick={cancelBodyEdit}>
                                            <TimesIcon />
                                        </Button>
                                    </>
                                ) : (
                                    <Button variant="plain" aria-label="Edit body"
                                        onClick={() => {
                                            const initial = subject.body || "";
                                            setEditBody(initial);
                                            editBodyRef.current = initial;
                                            setIsEditingBody(true);
                                        }}>
                                        <PencilAltIcon />
                                    </Button>
                                )}
                            </div>
                            {bodySaveError && (
                                <p style={{ color: "var(--pf-t--global--color--status--danger--default)", marginBottom: "8px" }}>
                                    {bodySaveError}
                                </p>
                            )}
                            {isEditingBody ? (
                                <div style={{ flex: "1 1 auto", minHeight: 0 }}>
                                    <CodeEditor
                                        code={editBody}
                                        onCodeChange={(v) => { editBodyRef.current = v; }}
                                        language={Language.markdown}
                                        isFullHeight
                                        height="100%"
                                        isDarkTheme={effectiveTheme === "dark"}
                                        isLineNumbersVisible
                                        // Run Monaco uncontrolled: PatternFly otherwise feeds a
                                        // lagging `value` back into the editor while typing, which
                                        // replaces the whole document and jumps the cursor to the
                                        // end. Seeding `defaultValue` and clearing `value` keeps the
                                        // editor authoritative over its own content.
                                        editorProps={{ value: undefined, defaultValue: editBody }}
                                        onEditorDidMount={(editor, monaco) => {
                                            editor.onKeyDown((e) => {
                                                if (e.keyCode === monaco.KeyCode.Escape) {
                                                    e.preventDefault();
                                                    e.stopPropagation();
                                                    cancelBodyEdit();
                                                }
                                            });
                                        }}
                                    />
                                </div>
                            ) : subject.body ? (
                                <div className="axiom-markdown">
                                    <Content>
                                        <Markdown remarkPlugins={[remarkGfm]} components={markdownMermaidComponents}>
                                            {subject.body}
                                        </Markdown>
                                    </Content>
                                </div>
                            ) : (
                                <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                                    No body content. Click the edit button to add markdown content.
                                </p>
                            )}
                        </div>
                    )}
                    {activeTab === RUNS_TAB && <RunsTimeline runs={runs} onRerun={loadData} />}
                    {activeTab === 1 && (
                        <TasksTab tasks={tasks} subjectId={id} agentNames={agentNames} onRefresh={loadData} />
                    )}
                    {activeTab === 2 && <ThreadTab entries={thread} />}
                    {activeTab === 3 && <EventsTab events={events} />}
                    {activeTab === 4 && <MetricsTab metrics={metrics} />}
                </div>
            </div>

            <CreateSessionModal
                isOpen={isAssistantModalOpen}
                onClose={() => setIsAssistantModalOpen(false)}
                onSessionCreated={(session) => {
                    setIsAssistantModalOpen(false);
                    window.open(`/assistant/${session.id}?breakout=true`, "_blank");
                }}
                subjectId={subject.id}
                defaultName={subject.name}
            />

            <ConfirmDeleteModal isOpen={isDeleteOpen} title="Delete Subject"
                onConfirm={() => {
                    deleteSubject(id)
                        .then(() => navigate("/subjects"))
                        .catch(console.error);
                }}
                onCancel={() => setIsDeleteOpen(false)}>
                Delete this subject and all its data? This will remove all tasks,
                activity, thread entries, and workspace files. This cannot be undone.
            </ConfirmDeleteModal>

            <EditLabelsModal
                isOpen={isLabelsOpen}
                labels={subject.labels || []}
                onSave={async (labels) => {
                    const updated = await updateSubject(id, { labels });
                    setSubject(updated);
                }}
                onClose={() => setIsLabelsOpen(false)}
            />
        </PageSection>
    );
}

/** Renders the subject's workflow runs, newest first; failed runs can be re-run. */
function RunsTimeline({ runs, onRerun }: { runs: WorkflowRunSummary[]; onRerun: () => void }) {
    const [confirming, setConfirming] = useState<WorkflowRunSummary | null>(null);
    const [busy, setBusy] = useState<boolean>(false);
    const [result, setResult] = useState<{ variant: "success" | "danger"; title: string; runId?: number } | null>(null);

    const rerun = (run: WorkflowRunSummary) => {
        setBusy(true);
        rerunWorkflowRun(run.runId)
            .then((created) => {
                const newRunId: number = created.runId ?? created.id;
                setResult({ variant: "success", title: `Started run #${newRunId} as a re-run of run #${run.runId}.`,
                    runId: newRunId });
                onRerun();
            })
            .catch((e: unknown) => setResult({ variant: "danger",
                title: `Could not re-run run #${run.runId}: ${e instanceof Error ? e.message : String(e)}` }))
            .finally(() => {
                setBusy(false);
                setConfirming(null);
            });
    };

    if (runs.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No workflow runs for this subject yet.</EmptyStateBody>
            </EmptyState>
        );
    }
    return (
        <>
            {result && (
                <Alert isInline variant={result.variant} title={result.title} style={{ marginBottom: "16px" }}
                    actionClose={<AlertActionCloseButton onClose={() => setResult(null)} />}
                    actionLinks={result.runId !== undefined
                        ? <Link to={`/runs/${result.runId}`}>View run #{result.runId}</Link> : undefined} />
            )}
            <Table aria-label="Runs timeline" variant="compact">
                <Thead>
                    <Tr>
                        <Th>Status</Th>
                        <Th>Run</Th>
                        <Th>Started</Th>
                        <Th>Completed</Th>
                        <Th screenReaderText="Actions" />
                    </Tr>
                </Thead>
                <Tbody>
                    {runs.map((run) => (
                        <Tr key={run.runId}>
                            <Td>
                                <Label isCompact color={RUN_STATUS_COLORS[run.status] || "grey"}>
                                    {run.status}
                                </Label>
                            </Td>
                            <Td>
                                {/* The subject is this page, so a run is named by its workflow; a title goes below. */}
                                <Link to={`/runs/${run.runId}`}>
                                    {run.definitionName || `Workflow #${run.definitionId}`} v{run.definitionVersion}
                                </Link>
                                {run.title && <small className="axiom-run-title">{run.title}</small>}
                            </Td>
                            <Td style={{ whiteSpace: "nowrap" }}>{new Date(run.startedOn).toLocaleString()}</Td>
                            <Td style={{ whiteSpace: "nowrap" }}>
                                {run.completedOn ? new Date(run.completedOn).toLocaleString() : "—"}
                            </Td>
                            <Td isActionCell>
                                {run.status === "failed" && (
                                    <Button variant="secondary" size="sm" icon={<RedoIcon />}
                                        aria-label={`Re-run run #${run.runId}`}
                                        onClick={() => setConfirming(run)}>
                                        Re-run
                                    </Button>
                                )}
                            </Td>
                        </Tr>
                    ))}
                </Tbody>
            </Table>
            {confirming && (
                <Modal isOpen variant="small" onClose={() => setConfirming(null)} aria-labelledby="rerun-title"
                    elementToFocus="#rerun-confirm">
                    <ModalHeader title="Re-run workflow run" labelId="rerun-title" />
                    <ModalBody>
                        Start a new run of <strong>{confirming.definitionName || `Definition #${confirming.definitionId}`}</strong>{" "}
                        for this subject, using the latest published version of the workflow and the same inputs as
                        run #{confirming.runId}? The workflow&apos;s actions run again.
                    </ModalBody>
                    <ModalFooter>
                        <Button id="rerun-confirm" variant="primary" isLoading={busy} isDisabled={busy}
                            onClick={() => rerun(confirming)}>Re-run</Button>
                        <Button variant="link" onClick={() => setConfirming(null)}>Cancel</Button>
                    </ModalFooter>
                </Modal>
            )}
        </>
    );
}

function TasksTab({ tasks, subjectId, agentNames, onRefresh }: {
    tasks: Task[];
    subjectId: number;
    agentNames: Record<number, string>;
    onRefresh: () => void;
}) {
    const navigate = useNavigate();
    const [respondingTo, setRespondingTo] = useState<number | null>(null);
    const [respondItem, setRespondItem] = useState<InboxItem | null>(null);
    const [formValues, setFormValues] = useState<Record<string, unknown>>({});
    const [submitting, setSubmitting] = useState(false);

    // Execution log modal state
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);
    const [lineageTaskId, setLineageTaskId] = useState<number | null>(null);

    const handleViewLog = (taskId: number) => {
        setLogTaskId(taskId);
        setIsLogModalOpen(true);
    };

    const handleSubmitResponse = (taskId: number) => {
        setSubmitting(true);
        completeInboxItem(taskId, formValues)
            .then(() => {
                setRespondingTo(null);
                setRespondItem(null);
                setFormValues({});
                onRefresh();
            })
            .catch(console.error)
            .finally(() => setSubmitting(false));
    };

    if (tasks.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No tasks yet.</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <div>
            <Table aria-label="Tasks" variant="compact">
                <Thead>
                    <Tr>
                        <Th>Action</Th>
                        <Th>Agent</Th>
                        <Th>Status</Th>
                        <Th>Created By</Th>
                        <Th>Created</Th>
                        <Th>Completed</Th>
                        <Th>Trace</Th>
                        <Th />
                    </Tr>
                </Thead>
                <Tbody>
                    {tasks.map((task) => (
                        <Tr key={task.id}>
                            <Td>{task.actionType}</Td>
                            <Td>
                                {task.assignedAgent
                                    ? agentNames[task.assignedAgent] || `Agent #${task.assignedAgent}`
                                    : "—"}
                            </Td>
                            <Td>
                                <Label color={STATUS_COLORS[task.status] || "grey"}>
                                    {task.status}
                                </Label>
                            </Td>
                            <Td>{task.createdBy}</Td>
                            <Td>{new Date(task.createdOn).toLocaleString()}</Td>
                            <Td>
                                {task.completedOn
                                    ? new Date(task.completedOn).toLocaleString()
                                    : "—"}
                            </Td>
                            <Td>
                                {task.createdBy === "user" && task.traceId ? (
                                    <Button variant="link" isInline
                                        onClick={() => navigate(`/logs/traces/${task.traceId}`)}>
                                        View Trace
                                    </Button>
                                ) : task.createdBy === "user" ? "—" : ""}
                            </Td>
                            <Td>
                                <Button variant="link" size="sm"
                                    data-testid={`subject-task-lineage-${task.id}`}
                                    onClick={() => setLineageTaskId(task.id)}>
                                    Lineage
                                </Button>
                                {(task.status === "Completed" || task.status === "Failed") && (
                                    <Button
                                        variant="link"
                                        size="sm"
                                        onClick={() => handleViewLog(task.id)}
                                    >
                                        View Log
                                    </Button>
                                )}
                                {task.status === "AwaitingInput" && (
                                    <Button
                                        variant="secondary"
                                        size="sm"
                                        onClick={() => {
                                            setRespondingTo(task.id);
                                            setFormValues({});
                                            fetchInboxItem(task.id)
                                                .then(setRespondItem)
                                                .catch(console.error);
                                        }}
                                    >
                                        Respond
                                    </Button>
                                )}
                            </Td>
                        </Tr>
                    ))}
                </Tbody>
            </Table>

            {/* Response form for AwaitingInput task */}
            {respondingTo != null && (
                <Card style={{ marginTop: "16px" }}>
                    <CardBody>
                        <Title headingLevel="h4" size="md">
                            Respond to Task #{respondingTo}
                        </Title>
                        {respondItem?.humanContext?.details?.map((d) => (
                            <div key={d.label} style={{ marginTop: "8px" }}>
                                <strong>{d.label}:</strong>{" "}
                                {d.value?.trim()
                                    ? d.value
                                    : <span className="axiom-text-subtle">&mdash;</span>}
                            </div>
                        ))}
                        <div style={{ marginTop: "8px" }}>
                            <DynamicFormRenderer
                                schema={respondItem?.outputSchema}
                                values={formValues}
                                onChange={setFormValues}
                            />
                        </div>
                        <div style={{ marginTop: "8px" }}>
                            <Button
                                variant="primary"
                                onClick={() => handleSubmitResponse(respondingTo)}
                                isDisabled={submitting}
                                isLoading={submitting}
                                style={{ marginRight: "8px" }}
                            >
                                {submitting ? "Submitting..." : "Submit Response"}
                            </Button>
                            <Button
                                variant="link"
                                onClick={() => setRespondingTo(null)}
                            >
                                Cancel
                            </Button>
                        </div>
                    </CardBody>
                </Card>
            )}

            <ExecutionLogModal
                isOpen={isLogModalOpen}
                subjectId={subjectId}
                taskId={logTaskId}
                onClose={() => setIsLogModalOpen(false)}
            />

            <LineageModal entityType="task" id={lineageTaskId}
                title={`Lineage of task #${lineageTaskId}`}
                onClose={() => setLineageTaskId(null)} />
        </div>
    );
}

function ThreadTab({ entries }: { entries: ThreadEntry[] }) {
    if (entries.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No conversation yet.</EmptyStateBody>
            </EmptyState>
        );
    }

    const AUTHOR_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
        agent: "green",
        user: "orange",
        system: "grey",
    };

    return (
        <div>
            {entries.map((entry) => (
                <Card key={entry.id} isCompact style={{ marginBottom: "8px" }}>
                    <CardBody>
                        <Flex
                            justifyContent={{ default: "justifyContentSpaceBetween" }}
                            alignItems={{ default: "alignItemsCenter" }}
                            style={{ marginBottom: "8px" }}
                        >
                            <FlexItem>
                                <Label
                                    isCompact
                                    color={AUTHOR_COLORS[entry.authorType] || "grey"}
                                >
                                    {entry.authorType}
                                </Label>
                                <Label isCompact style={{ marginLeft: "8px" }}>
                                    {entry.entryType}
                                </Label>
                            </FlexItem>
                            <FlexItem>
                                <span className="axiom-text-subtle" style={{ fontSize: "12px" }}>
                                    {new Date(entry.createdOn).toLocaleString()}
                                </span>
                            </FlexItem>
                        </Flex>
                        <div className="axiom-markdown">
                            <Content>
                                <Markdown remarkPlugins={[remarkGfm]} components={markdownMermaidComponents}>{entry.content}</Markdown>
                            </Content>
                        </div>
                    </CardBody>
                </Card>
            ))}
        </div>
    );
}

const EVENT_TYPE_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    "issue-created": "blue",
    "issue-updated": "orange",
    "issue-closed": "grey",
    "issue-reopened": "green",
    "comment-added": "blue",
    "task-completed": "green",
    "task-failed": "red",
};

function EventsTab({ events }: { events: StreamEvent[] }) {
    if (events.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No events recorded for this subject.</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <Table aria-label="Subject Events" variant="compact">
            <Thead>
                <Tr>
                    <Th>Time</Th>
                    <Th>Source</Th>
                    <Th>Event Type</Th>
                    <Th>Ref</Th>
                </Tr>
            </Thead>
            <Tbody>
                {events.map((event) => (
                    <Tr key={event.id}>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {new Date(event.timestamp).toLocaleString()}
                        </Td>
                        <Td>
                            <Label isCompact>{event.source}</Label>
                        </Td>
                        <Td>
                            <Label isCompact
                                color={EVENT_TYPE_COLORS[event.type] || "grey"}>
                                {event.type}
                            </Label>
                        </Td>
                        <Td>{event.ref || "—"}</Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}

function MetricsTab({ metrics }: { metrics: SubjectMetrics | null }) {
    if (!metrics) {
        return (
            <EmptyState>
                <EmptyStateBody>Loading metrics...</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <Gallery hasGutter minWidths={{ default: "180px" }}>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {formatBytes(metrics.diskUsageBytes)}
                        </div>
                        <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>Disk Usage</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            ${metrics.totalCostUsd.toFixed(4)}
                        </div>
                        <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>AI Cost</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.invocationCount}
                        </div>
                        <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>AI Invocations</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.totalInputTokens.toLocaleString()}
                        </div>
                        <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>Input Tokens</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.totalOutputTokens.toLocaleString()}
                        </div>
                        <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>Output Tokens</div>
                    </CardBody>
                </Card>
            </GalleryItem>
        </Gallery>
    );
}
