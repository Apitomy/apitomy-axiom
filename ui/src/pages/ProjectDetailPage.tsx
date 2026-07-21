import { useState, useEffect, useCallback } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
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
    Form,
    Gallery,
    GalleryItem,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import ChatIcon from "@patternfly/react-icons/dist/esm/icons/chat-icon";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import GithubIcon from "@patternfly/react-icons/dist/esm/icons/github-icon";
import JiraIcon from "@patternfly/react-icons/dist/esm/icons/jira-icon";
import {
    type ActionType,
    type AxiomEvent,
    type Project,
    type ProjectMetrics,
    type Task,
    type ThreadEntry,
    fetchActionTypes,
    fetchActors,
    fetchProject,
    fetchProjectEvents,
    fetchProjectMetrics,
    fetchProjectTasks,
    fetchThreadEntries,
    createTask,
    deleteProject,
    updateProject,
    formatBytes,
    respondToTask,
} from "../config/api";
import { EditLabelsModal } from "../components/EditLabelsModal";
import { LabelDisplay } from "../components/LabelDisplay";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { CreateSessionModal } from "../components/assistant/CreateSessionModal";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Created: "blue",
    InProgress: "green",
    Idle: "orange",
    Completed: "grey",
    Pending: "blue",
    AwaitingInput: "orange",
    Failed: "red",
    Cancelled: "grey",
};

export function ProjectDetailPage() {
    const { projectId } = useParams<{ projectId: string }>();
    const navigate = useNavigate();
    const [project, setProject] = useState<Project | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [thread, setThread] = useState<ThreadEntry[]>([]);
    const [events, setEvents] = useState<AxiomEvent[]>([]);
    const [metrics, setMetrics] = useState<ProjectMetrics | null>(null);
    const [actorNames, setActorNames] = useState<Record<number, string>>({});
    const [activeTab, setActiveTab] = useState(0);
    const [loading, setLoading] = useState(true);

    const [isDeleteOpen, setIsDeleteOpen] = useState(false);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);

    // Trigger Action state
    const [isActionModalOpen, setIsActionModalOpen] = useState(false);
    const [isAssistantModalOpen, setIsAssistantModalOpen] = useState(false);
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [selectedActionType, setSelectedActionType] = useState("");
    const [actionInput, setActionInput] = useState("");
    const [submitting, setSubmitting] = useState(false);

    const id = Number(projectId);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        Promise.all([
            fetchProject(id),
            fetchProjectTasks(id),
            fetchThreadEntries(id),
            fetchProjectEvents(id),
            fetchProjectMetrics(id),
            fetchActors(),
        ])
            .then(([p, t, th, ev, m, actors]) => {
                setProject(p);
                setTasks(t);
                setThread(th);
                setEvents(ev);
                setMetrics(m);
                const names: Record<number, string> = {};
                actors.forEach((a) => { names[a.id] = a.name; });
                setActorNames(names);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const openActionModal = () => {
        fetchActionTypes()
            .then((types) => {
                const triggerable = types.filter(
                    (t) => t.userTriggerable
                );
                setActionTypes(triggerable);
                if (triggerable.length > 0) {
                    setSelectedActionType(triggerable[0].name);
                }
                setActionInput("");
                setIsActionModalOpen(true);
            })
            .catch(console.error);
    };

    const handleTriggerAction = () => {
        if (!selectedActionType) return;
        setSubmitting(true);
        createTask(id, {
            actionType: selectedActionType,
            input: actionInput || undefined,
        })
            .then(() => {
                setIsActionModalOpen(false);
                loadData();
            })
            .catch(console.error)
            .finally(() => setSubmitting(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading project...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    if (!project) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Project not found.</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    const selectedActionDesc = actionTypes.find(
        (t) => t.name === selectedActionType
    )?.description;

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/">Dashboard</Link></BreadcrumbItem>
                <BreadcrumbItem><Link to="/projects">Projects</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{project.name}</BreadcrumbItem>
            </Breadcrumb>

            {/* Project header */}
            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {project.issueSource === "github" && <GithubIcon style={{ marginRight: 8 }} />}
                        {project.issueSource === "jira" && <JiraIcon style={{ marginRight: 8 }} />}
                        {project.type === "issue" && <BugIcon style={{ marginRight: 8 }} />}
                        {project.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 8 }} />}
                        {project.name}
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
                    <Button
                        variant="secondary"
                        icon={<PlayIcon />}
                        onClick={openActionModal}
                        style={{ marginRight: "8px" }}
                    >
                        Trigger Action
                    </Button>
                    {project.status === "Completed" && (
                        <Button variant="danger" icon={<TrashIcon />}
                            onClick={() => setIsDeleteOpen(true)}
                            style={{ marginRight: "8px" }}>
                            Delete
                        </Button>
                    )}
                    <Label color={STATUS_COLORS[project.status] || "grey"}>
                        {project.status}
                    </Label>
                </FlexItem>
            </Flex>

            {/* Project metadata */}
            <DescriptionList isHorizontal isCompact columnModifier={{ default: "3Col" }}
                style={{ marginTop: "16px" }}>
                <DescriptionListGroup>
                    <DescriptionListTerm>Type</DescriptionListTerm>
                    <DescriptionListDescription>
                        <Label isCompact>{project.type}</Label>
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Issue</DescriptionListTerm>
                    <DescriptionListDescription>
                        {project.issueSource === "github" && project.issueRef ? (
                            <a href={`https://github.com/${project.issueRef.replace("#", "/issues/")}`}
                                target="_blank" rel="noopener noreferrer">
                                {project.issueRef}
                            </a>
                        ) : (
                            project.issueRef
                        )}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Repository</DescriptionListTerm>
                    <DescriptionListDescription>
                        {project.repository}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Source</DescriptionListTerm>
                    <DescriptionListDescription>
                        {project.issueSource}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Created</DescriptionListTerm>
                    <DescriptionListDescription>
                        {new Date(project.createdOn).toLocaleString()}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Updated</DescriptionListTerm>
                    <DescriptionListDescription>
                        {new Date(project.updatedOn).toLocaleString()}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Workspace</DescriptionListTerm>
                    <DescriptionListDescription>
                        <code style={{ fontSize: "13px" }}>
                            ~/.axiom/workspaces/project-{project.id}
                        </code>
                    </DescriptionListDescription>
                </DescriptionListGroup>
                {project.description && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Description</DescriptionListTerm>
                        <DescriptionListDescription>
                            {project.description}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                )}
                <DescriptionListGroup>
                    <DescriptionListTerm>Labels</DescriptionListTerm>
                    <DescriptionListDescription>
                        <LabelDisplay labels={project.labels || []}
                            onEdit={() => setIsLabelsOpen(true)} />
                    </DescriptionListDescription>
                </DescriptionListGroup>
            </DescriptionList>

            {/* Tabs */}
            <div style={{ marginTop: "24px" }}>
                <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                    <Tab eventKey={0} title={<TabTitleText>Tasks ({tasks.length})</TabTitleText>}>
                        <TabContent id="tasks-tab" eventKey={0} activeKey={activeTab} style={{ marginTop: "16px" }}>
                            <TasksTab tasks={tasks} projectId={id} actorNames={actorNames} onRefresh={loadData} />
                        </TabContent>
                    </Tab>
                    <Tab eventKey={1} title={<TabTitleText>Thread ({thread.length})</TabTitleText>}>
                        <TabContent id="thread-tab" eventKey={1} activeKey={activeTab} style={{ marginTop: "16px" }}>
                            <ThreadTab entries={thread} />
                        </TabContent>
                    </Tab>
                    <Tab eventKey={2} title={<TabTitleText>Events ({events.length})</TabTitleText>}>
                        <TabContent id="events-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "16px" }}>
                            <EventsTab events={events} />
                        </TabContent>
                    </Tab>
                    <Tab eventKey={3} title={<TabTitleText>Metrics</TabTitleText>}>
                        <TabContent id="metrics-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "16px" }}>
                            <MetricsTab metrics={metrics} />
                        </TabContent>
                    </Tab>
                </Tabs>
            </div>

            {/* Trigger Action Modal */}
            <Modal
                isOpen={isActionModalOpen}
                onClose={() => setIsActionModalOpen(false)}
                variant="medium"
            >
                <ModalHeader title="Trigger Action" />
                <ModalBody>
                    {actionTypes.length === 0 ? (
                        <EmptyState>
                            <EmptyStateBody>
                                No user-triggerable action types configured.
                            </EmptyStateBody>
                        </EmptyState>
                    ) : (
                        <Form>
                            <FormGroup label="Action Type" isRequired fieldId="actionType">
                                <FormSelect
                                    id="actionType"
                                    value={selectedActionType}
                                    onChange={(_e, v) => setSelectedActionType(v)}
                                >
                                    {actionTypes.map((at) => (
                                        <FormSelectOption
                                            key={at.name}
                                            value={at.name}
                                            label={at.name}
                                        />
                                    ))}
                                </FormSelect>
                            </FormGroup>
                            {selectedActionDesc && (
                                <p style={{ color: "#6a6e73", fontSize: "14px", marginTop: "-8px" }}>
                                    {selectedActionDesc}
                                </p>
                            )}
                            <FormGroup label="Instructions (optional)" fieldId="input">
                                <TextArea
                                    id="input"
                                    placeholder="Additional context or instructions for the actor..."
                                    value={actionInput}
                                    onChange={(_e, v) => setActionInput(v)}
                                    rows={4}
                                />
                            </FormGroup>
                        </Form>
                    )}
                </ModalBody>
                <ModalFooter>
                    <Button
                        variant="primary"
                        onClick={handleTriggerAction}
                        isDisabled={!selectedActionType || submitting}
                        isLoading={submitting}
                    >
                        {submitting ? "Creating..." : "Trigger"}
                    </Button>
                    <Button
                        variant="link"
                        onClick={() => setIsActionModalOpen(false)}
                    >
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <CreateSessionModal
                isOpen={isAssistantModalOpen}
                onClose={() => setIsAssistantModalOpen(false)}
                onSessionCreated={(session) => {
                    setIsAssistantModalOpen(false);
                    window.open(`/assistant/${session.id}?breakout=true`, "_blank");
                }}
                projectId={project.id}
                defaultName={project.name}
            />

            <ConfirmDeleteModal isOpen={isDeleteOpen} title="Delete Project"
                onConfirm={() => {
                    deleteProject(id)
                        .then(() => navigate("/projects"))
                        .catch(console.error);
                }}
                onCancel={() => setIsDeleteOpen(false)}>
                Delete this project and all its data? This will remove all tasks,
                activity, thread entries, and workspace files. This cannot be undone.
            </ConfirmDeleteModal>

            <EditLabelsModal
                isOpen={isLabelsOpen}
                labels={project.labels || []}
                onSave={async (labels) => {
                    const updated = await updateProject(id, { labels });
                    setProject(updated);
                }}
                onClose={() => setIsLabelsOpen(false)}
            />
        </PageSection>
    );
}

function TasksTab({ tasks, projectId, actorNames, onRefresh }: {
    tasks: Task[];
    projectId: number;
    actorNames: Record<number, string>;
    onRefresh: () => void;
}) {
    const navigate = useNavigate();
    const [respondingTo, setRespondingTo] = useState<number | null>(null);
    const [responseText, setResponseText] = useState("");
    const [submitting, setSubmitting] = useState(false);

    // Execution log modal state
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);

    const handleViewLog = (taskId: number) => {
        setLogTaskId(taskId);
        setIsLogModalOpen(true);
    };

    const handleSubmitResponse = (taskId: number) => {
        setSubmitting(true);
        respondToTask(projectId, taskId, responseText)
            .then(() => {
                setRespondingTo(null);
                setResponseText("");
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
                        <Th>Actor</Th>
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
                                {task.assignedActor
                                    ? actorNames[task.assignedActor] || `Actor #${task.assignedActor}`
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
                                            setResponseText("");
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
                        <TextArea
                            id="task-response"
                            placeholder="Enter your response..."
                            value={responseText}
                            onChange={(_e, v) => setResponseText(v)}
                            rows={4}
                            style={{ marginTop: "8px" }}
                        />
                        <div style={{ marginTop: "8px" }}>
                            <Button
                                variant="primary"
                                onClick={() => handleSubmitResponse(respondingTo)}
                                isDisabled={!responseText.trim() || submitting}
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
                projectId={projectId}
                taskId={logTaskId}
                onClose={() => setIsLogModalOpen(false)}
            />
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
        manager: "blue",
        actor: "green",
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
                                <span style={{ fontSize: "12px", color: "#6a6e73" }}>
                                    {new Date(entry.createdOn).toLocaleString()}
                                </span>
                            </FlexItem>
                        </Flex>
                        <Content>
                            <Markdown remarkPlugins={[remarkGfm]}>{entry.content}</Markdown>
                        </Content>
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

function EventsTab({ events }: { events: AxiomEvent[] }) {
    const navigate = useNavigate();

    if (events.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No events recorded for this project.</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <Table aria-label="Project Events" variant="compact">
            <Thead>
                <Tr>
                    <Th>Time</Th>
                    <Th>Source</Th>
                    <Th>Event Type</Th>
                    <Th>Trace</Th>
                </Tr>
            </Thead>
            <Tbody>
                {events.map((event) => (
                    <Tr key={event.id}>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {new Date(event.receivedAt).toLocaleString()}
                        </Td>
                        <Td>
                            <Label isCompact>{event.source}</Label>
                        </Td>
                        <Td>
                            <Label isCompact
                                color={EVENT_TYPE_COLORS[event.eventType] || "grey"}>
                                {event.eventType}
                            </Label>
                        </Td>
                        <Td>
                            {event.traceId ? (
                                <Button variant="link" isInline
                                    onClick={() => navigate(`/logs/traces/${event.traceId}`)}>
                                    View Trace
                                </Button>
                            ) : "—"}
                        </Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}

function MetricsTab({ metrics }: { metrics: ProjectMetrics | null }) {
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
                        <div style={{ fontSize: "13px", color: "#6a6e73" }}>Disk Usage</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            ${metrics.totalCostUsd.toFixed(4)}
                        </div>
                        <div style={{ fontSize: "13px", color: "#6a6e73" }}>AI Cost</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.invocationCount}
                        </div>
                        <div style={{ fontSize: "13px", color: "#6a6e73" }}>AI Invocations</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.totalInputTokens.toLocaleString()}
                        </div>
                        <div style={{ fontSize: "13px", color: "#6a6e73" }}>Input Tokens</div>
                    </CardBody>
                </Card>
            </GalleryItem>
            <GalleryItem>
                <Card isCompact>
                    <CardBody style={{ textAlign: "center", padding: "16px" }}>
                        <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                            {metrics.totalOutputTokens.toLocaleString()}
                        </div>
                        <div style={{ fontSize: "13px", color: "#6a6e73" }}>Output Tokens</div>
                    </CardBody>
                </Card>
            </GalleryItem>
        </Gallery>
    );
}
