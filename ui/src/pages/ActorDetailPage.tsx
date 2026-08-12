import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
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
    MenuToggle,
    MenuToggleElement,
    PageSection,
    Pagination,
    Select,
    SelectOption,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem, HelperText, HelperTextItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import {
    type Actor,
    type ActionType,
    type Task,
    fetchActor,
    fetchActorTasks,
    fetchActionTypes,
    updateActor,
} from "../config/api";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { TypeaheadAddInput, type TypeaheadAddSuggestion } from "../components/TypeaheadAddInput";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Pending: "blue",
    InProgress: "green",
    AwaitingInput: "orange",
    Completed: "grey",
    Failed: "red",
    Cancelled: "grey",
};

const TYPE_COLORS: Record<string, "blue" | "green" | "orange"> = {
    "ai-agent": "blue",
    "human": "green",
};

export function ActorDetailPage() {
    const { actorId } = useParams<{ actorId: string }>();
    const id = Number(actorId);

    const [actor, setActor] = useState<Actor | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    // Editable form state
    const [description, setDescription] = useState("");
    const [capabilities, setCapabilities] = useState<string[]>([]);

    // Task history state
    const [tasks, setTasks] = useState<Task[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [filterActionType, setFilterActionType] = useState("");
    const [filterStatus, setFilterStatus] = useState<string[]>([]);
    const [isStatusSelectOpen, setIsStatusSelectOpen] = useState(false);
    const [tasksLoading, setTasksLoading] = useState(false);

    // Execution log modal
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logProjectId, setLogProjectId] = useState<number | null>(null);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);

    const loadActor = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchActor(id)
            .then((a) => {
                setActor(a);
                setDescription(a.description || "");
                setCapabilities(a.capabilities || []);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    const handleSave = () => {
        if (!actor) return;
        setSaving(true);
        updateActor(id, {
            name: actor.name,
            type: actor.type,
            description,
            capabilities,
        })
            .then((updated) => {
                setActor(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const loadTasks = useCallback(() => {
        if (!id) return;
        setTasksLoading(true);
        fetchActorTasks(
            id, page, perPage,
            filterActionType || undefined,
            filterStatus.length > 0 ? filterStatus.join(",") : undefined
        )
            .then((results) => {
                setTasks(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setTasksLoading(false));
    }, [id, page, perPage, filterActionType, filterStatus]);

    useEffect(() => { loadActor(); }, [loadActor]);
    useEffect(() => { loadTasks(); }, [loadTasks]);

    const handleViewLog = (projectId: number, taskId: number) => {
        setLogProjectId(projectId);
        setLogTaskId(taskId);
        setIsLogModalOpen(true);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading actor...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!actor) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Actor not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/actors">Actors</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{actor.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{actor.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Flex alignItems={{ default: "alignItemsCenter" }} spaceItems={{ default: "spaceItemsMd" }}>
                        <FlexItem>
                            <Label color={TYPE_COLORS[actor.type] || "grey"}>{actor.type}</Label>
                        </FlexItem>
                        <FlexItem>
                            <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                                isDisabled={!dirty || saving} isLoading={saving}>
                                {saving ? "Saving..." : "Save Changes"}
                            </Button>
                        </FlexItem>
                    </Flex>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <InfoTab
                            actor={actor}
                            description={description}
                            onDescriptionChange={(v) => { setDescription(v); setDirty(true); }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Capabilities ({capabilities.length})</TabTitleText>}>
                    <TabContent id="capabilities-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <CapabilitiesTab
                            capabilities={capabilities}
                            onAdd={(c) => {
                                if (c && !capabilities.includes(c)) {
                                    setCapabilities([...capabilities, c]);
                                    setDirty(true);
                                }
                            }}
                            onRemove={(c) => {
                                setCapabilities(capabilities.filter((x) => x !== c));
                                setDirty(true);
                            }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Task History ({totalCount})</TabTitleText>}>
                    <TabContent id="tasks-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "16px" }}>
                        <TaskHistoryTab
                            tasks={tasks}
                            totalCount={totalCount}
                            page={page}
                            perPage={perPage}
                            filterActionType={filterActionType}
                            filterStatus={filterStatus}
                            isStatusSelectOpen={isStatusSelectOpen}
                            loading={tasksLoading}
                            onSetPage={setPage}
                            onSetPerPage={(pp) => { setPerPage(pp); setPage(1); }}
                            onFilterActionType={(v) => { setFilterActionType(v); setPage(1); }}
                            onFilterStatus={setFilterStatus}
                            onStatusSelectToggle={setIsStatusSelectOpen}
                            onRefresh={loadTasks}
                            onViewLog={handleViewLog}
                        />
                    </TabContent>
                </Tab>
            </Tabs>

            <ExecutionLogModal
                isOpen={isLogModalOpen}
                projectId={logProjectId}
                taskId={logTaskId}
                onClose={() => setIsLogModalOpen(false)}
            />
        </PageSection>
    );
}

function InfoTab({ actor, description, onDescriptionChange }: {
    actor: Actor;
    description: string;
    onDescriptionChange: (v: string) => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" fieldId="name">
                <TextInput id="name" value={actor.name} isDisabled />
            </FormGroup>
            <FormGroup label="Type" fieldId="type">
                <TextInput id="type" value={actor.type} isDisabled />
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={description}
                    onChange={(_e, v) => onDescriptionChange(v)} rows={3} />
            </FormGroup>
        </Form>
    );
}

function CapabilitiesTab({ capabilities, onAdd, onRemove }: {
    capabilities: string[];
    onAdd: (c: string) => void;
    onRemove: (c: string) => void;
}) {
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);

    useEffect(() => {
        fetchActionTypes(1, 1000).then((r) => setActionTypes(r.items)).catch(console.error);
    }, []);

    const suggestions: TypeaheadAddSuggestion[] = actionTypes.map((at) => ({
        value: at.name,
    }));

    return (
        <div style={{ maxWidth: "700px" }}>
            <HelperText>
                <HelperTextItem>
                    Define which action types this actor can perform. Capabilities
                    determine which tasks may be assigned to this actor.
                </HelperTextItem>
            </HelperText>

            <div style={{ marginBottom: "16px" }}>
                <TypeaheadAddInput
                    onAdd={onAdd}
                    suggestions={suggestions}
                    existingItems={capabilities}
                    placeholder="Type an action type name and press Enter"
                />
            </div>

            {capabilities.length === 0 ? (
                <EmptyState variant="xs">
                    <EmptyStateBody>
                        No capabilities configured.
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                    {capabilities.map((c) => (
                        <Flex key={c} alignItems={{ default: "alignItemsCenter" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                            }}>
                            <FlexItem grow={{ default: "grow" }}>
                                <code style={{ fontSize: "13px" }}>{c}</code>
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" size="sm"
                                    onClick={() => onRemove(c)}
                                    aria-label={`Remove ${c}`}>
                                    <TimesIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                    ))}
                </div>
            )}
        </div>
    );
}

function TaskHistoryTab({ tasks, totalCount, page, perPage, filterActionType, filterStatus,
        isStatusSelectOpen, loading, onSetPage, onSetPerPage, onFilterActionType,
        onFilterStatus, onStatusSelectToggle, onRefresh, onViewLog }: {
    tasks: Task[];
    totalCount: number;
    page: number;
    perPage: number;
    filterActionType: string;
    filterStatus: string[];
    isStatusSelectOpen: boolean;
    loading: boolean;
    onSetPage: (p: number) => void;
    onSetPerPage: (pp: number) => void;
    onFilterActionType: (v: string) => void;
    onFilterStatus: (v: string[]) => void;
    onStatusSelectToggle: (open: boolean) => void;
    onRefresh: () => void;
    onViewLog: (projectId: number, taskId: number) => void;
}) {
    const hasActiveFilters = filterActionType || filterStatus.length > 0;

    const clearFilters = () => {
        onFilterActionType("");
        onFilterStatus([]);
    };

    const onStatusSelect = (_event: React.MouseEvent | undefined,
                             value: string | number | undefined) => {
        const val = value as string;
        onFilterStatus(
            filterStatus.includes(val)
                ? filterStatus.filter((s) => s !== val)
                : [...filterStatus, val]
        );
        onSetPage(1);
    };

    const statusToggle = (toggleRef: React.Ref<MenuToggleElement>) => (
        <MenuToggle
            ref={toggleRef}
            onClick={() => onStatusSelectToggle(!isStatusSelectOpen)}
            isExpanded={isStatusSelectOpen}
            style={{ minWidth: "150px" }}
        >
            {filterStatus.length > 0
                ? `${filterStatus.length} status${filterStatus.length > 1 ? "es" : ""} selected`
                : "Status"}
        </MenuToggle>
    );

    return (
        <div>
            <Toolbar clearAllFilters={clearFilters}>
                <ToolbarContent>
                    <ToolbarItem>
                        <TextInput
                            type="text"
                            aria-label="Filter by action type"
                            placeholder="Action type"
                            value={filterActionType}
                            onChange={(_e, v) => onFilterActionType(v)}
                            style={{ width: "180px" }}
                        />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Select
                            aria-label="Filter by status"
                            toggle={statusToggle}
                            onSelect={onStatusSelect}
                            selected={filterStatus}
                            isOpen={isStatusSelectOpen}
                            onOpenChange={onStatusSelectToggle}
                        >
                            {["Pending", "InProgress", "AwaitingInput", "Completed", "Failed"].map(
                                (status) => (
                                    <SelectOption key={status} value={status} hasCheckbox
                                        isSelected={filterStatus.includes(status)}>
                                        <Label isCompact color={STATUS_COLORS[status] || "grey"}>
                                            {status}
                                        </Label>
                                    </SelectOption>
                                )
                            )}
                        </Select>
                    </ToolbarItem>
                    {hasActiveFilters && (
                        <ToolbarItem>
                            <Button variant="link" icon={<TimesIcon />} onClick={clearFilters}>
                                Clear filters
                            </Button>
                        </ToolbarItem>
                    )}
                    <ToolbarItem variant="separator" />
                    <ToolbarItem>
                        <Button variant="plain" aria-label="Refresh" onClick={onRefresh}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            page={page}
                            perPage={perPage}
                            onSetPage={(_e, p) => onSetPage(p)}
                            onPerPageSelect={(_e, pp) => onSetPerPage(pp)}
                            isCompact
                        />
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>

            {loading ? (
                <EmptyState>
                    <EmptyStateBody>Loading tasks...</EmptyStateBody>
                </EmptyState>
            ) : tasks.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        {hasActiveFilters
                            ? "No tasks match the current filters."
                            : "No tasks have been assigned to this actor yet."}
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Actor Tasks" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Action Type</Th>
                            <Th>Project</Th>
                            <Th>Status</Th>
                            <Th>Created</Th>
                            <Th>Completed</Th>
                            <Th />
                        </Tr>
                    </Thead>
                    <Tbody>
                        {tasks.map((task) => (
                            <Tr key={task.id}>
                                <Td>{task.actionType}</Td>
                                <Td>
                                    <Link to={`/projects/${task.projectId}`}>
                                        Project #{task.projectId}
                                    </Link>
                                </Td>
                                <Td>
                                    <Label isCompact color={STATUS_COLORS[task.status] || "grey"}>
                                        {task.status}
                                    </Label>
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(task.createdOn).toLocaleString()}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {task.completedOn
                                        ? new Date(task.completedOn).toLocaleString()
                                        : "—"}
                                </Td>
                                <Td>
                                    {(task.status === "Completed" || task.status === "Failed") && (
                                        <Button variant="link" isInline
                                            onClick={() => onViewLog(task.projectId, task.id)}>
                                            View Log
                                        </Button>
                                    )}
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </div>
    );
}
