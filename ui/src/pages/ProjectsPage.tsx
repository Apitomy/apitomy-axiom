import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Pagination,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    Tooltip,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import EyeIcon from "@patternfly/react-icons/dist/esm/icons/eye-icon";
import EyeSlashIcon from "@patternfly/react-icons/dist/esm/icons/eye-slash-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import { ColoredLabel } from "../components/ColoredLabel";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";
import BugIcon from "@patternfly/react-icons/dist/esm/icons/bug-icon";
import GithubIcon from "@patternfly/react-icons/dist/esm/icons/github-icon";
import JiraIcon from "@patternfly/react-icons/dist/esm/icons/jira-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type Project,
    type NewProject,
    fetchProjects,
    createProject,
    deleteProject,
} from "../config/api";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    Created: "blue",
    InProgress: "green",
    Idle: "orange",
    Completed: "grey",
};

const STATUS_LABELS: Record<string, string> = {
    Created: "Created",
    InProgress: "In Progress",
    Idle: "Idle",
    Completed: "Completed",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "project-filter-name" },
    { value: "status", label: "Status", testId: "project-filter-status" },
    { value: "labels", label: "Labels", testId: "project-filter-labels" },
];

export function ProjectsPage() {
    const navigate = useNavigate();
    const [projects, setProjects] = useState<Project[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [showCompleted, setShowCompleted] = useState(
        () => localStorage.getItem("axiom.projects.showCompleted") === "true"
    );

    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newProject, setNewProject] = useState<NewProject>({
        name: "",
        type: "other",
        issueSource: "github",
        issueRef: "",
        repository: "",
    });

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const explicitStatusFilters = filters
        .filter((f) => f.filterBy.value === "status")
        .map((f) => f.filterValue)
        .join(",");
    const nonCompletedStatuses = Object.keys(STATUS_LABELS)
        .filter((s) => s !== "Completed")
        .join(",");
    const filterStatus = explicitStatusFilters
        ? explicitStatusFilters
        : (!showCompleted ? nonCompletedStatuses : undefined);
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0 || !showCompleted;

    const loadProjects = useCallback(() => {
        setLoading(true);
        fetchProjects(
            page, perPage,
            filterName || undefined,
            filterStatus || undefined,
            filterLabels || undefined
        )
            .then((results) => {
                setProjects(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, filterStatus, filterLabels]);

    useEffect(() => {
        loadProjects();
    }, [loadProjects]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "name") {
            const withoutName = updated.filter((f) => f.filterBy.value !== "name");
            withoutName.push(criteria);
            setFilters(withoutName);
        } else {
            updated.push(criteria);
            setFilters(updated);
        }
        setPage(1);
    };

    const onRemoveFilterCriteria = (criteria: ChipFilterCriteria) => {
        setFilters(filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue)));
        setPage(1);
    };

    const onClearAllFilters = () => {
        setFilters([]);
        setPage(1);
    };

    const addLabelFilter = (label: string) => {
        const already = filters.some((f) => f.filterBy.value === "labels" && f.filterValue === label);
        if (!already) {
            const labelType = FILTER_TYPES.find((t) => t.value === "labels")!;
            setFilters([...filters, { filterBy: labelType, filterValue: label }]);
            setPage(1);
        }
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteProject(deleteTarget).then(loadProjects).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleCreate = () => {
        createProject(newProject)
            .then(() => {
                setIsModalOpen(false);
                setNewProject({
                    name: "",
                    type: "other",
                    issueSource: "github",
                    issueRef: "",
                    repository: "",
                });
                loadProjects();
            })
            .catch(console.error);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg">Projects</Title>

            <Toolbar style={{ marginTop: "16px" }}>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput
                            filterTypes={FILTER_TYPES}
                            onAddCriteria={onAddFilterCriteria} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadProjects}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem>
                        <Tooltip content={showCompleted ? "Hide completed projects" : "Show completed projects"}>
                            <Button variant="control" aria-label="Toggle completed projects"
                                onClick={() => {
                                    const next = !showCompleted;
                                    setShowCompleted(next);
                                    localStorage.setItem("axiom.projects.showCompleted", String(next));
                                    setPage(1);
                                }}>
                                {showCompleted ? <EyeIcon /> : <EyeSlashIcon />}
                            </Button>
                        </Tooltip>
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button
                            variant="primary"
                            icon={<PlusCircleIcon />}
                            onClick={() => setIsModalOpen(true)}
                        >
                            Create Project
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            page={page}
                            perPage={perPage}
                            onSetPage={(_e, p) => setPage(p)}
                            onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
                            isCompact
                        />
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>
            {isFiltered && (
                <Toolbar>
                    <ToolbarContent>
                        <ToolbarItem>
                            <FilterChips
                                criteria={filters}
                                onClearAllCriteria={onClearAllFilters}
                                onRemoveCriteria={onRemoveFilterCriteria} />
                        </ToolbarItem>
                    </ToolbarContent>
                </Toolbar>
            )}

            <div>
                {loading ? (
                    <EmptyState>
                        <EmptyStateBody>Loading projects...</EmptyStateBody>
                    </EmptyState>
                ) : projects.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {filters.length > 0
                                ? "No projects match the current filters."
                                : !showCompleted
                                    ? "All projects are completed. Toggle the eye icon to show them."
                                    : "No projects yet. Create one or wait for events from a monitored repository."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Projects" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Status</Th>
                                <Th>Issue</Th>
                                <Th>Labels</Th>
                                <Th>Updated</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {projects.map((project) => (
                                <Tr
                                    key={project.id}
                                    isClickable
                                    onRowClick={() => navigate(`/projects/${project.id}`)}
                                >
                                    <Td>
                                        {project.issueSource === "github" && <GithubIcon style={{ marginRight: 6 }} />}
                                        {project.issueSource === "jira" && <JiraIcon style={{ marginRight: 6 }} />}
                                        {project.type === "issue" && <BugIcon style={{ marginRight: 6 }} />}
                                        {project.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 6 }} />}
                                        {project.name}
                                    </Td>
                                    <Td>
                                        <Label isCompact={true} color={STATUS_COLORS[project.status] || "grey"}>
                                            {STATUS_LABELS[project.status] || project.status}
                                        </Label>
                                    </Td>
                                    <Td>{project.issueRef}</Td>
                                    <Td>
                                        {project.labels?.map((label) => (
                                            <ColoredLabel key={label} isCompact
                                                style={{ marginRight: "4px", cursor: "pointer" }}
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    addLabelFilter(label);
                                                }}>
                                                {label}
                                            </ColoredLabel>
                                        ))}
                                    </Td>
                                    <Td>{new Date(project.updatedOn).toLocaleString()}</Td>
                                    <Td>
                                        {project.status === "Completed" && (
                                            <Button variant="plain" size="sm" style={{ padding: 0 }}
                                                onClick={(e) => handleDelete(e, project.id)}>
                                                <TrashIcon />
                                            </Button>
                                        )}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            {/* Create Project Modal */}
            <Modal
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                variant="medium"
            >
                <ModalHeader title="Create Project" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput
                                id="name"
                                isRequired
                                value={newProject.name}
                                onChange={(_e, v) =>
                                    setNewProject({ ...newProject, name: v })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Body" fieldId="body">
                            <TextArea
                                id="body"
                                value={newProject.body || ""}
                                onChange={(_e, v) =>
                                    setNewProject({
                                        ...newProject,
                                        body: v,
                                    })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Type" isRequired fieldId="type">
                            <FormSelect
                                id="type"
                                value={newProject.type}
                                onChange={(_e, v) =>
                                    setNewProject({ ...newProject, type: v })
                                }
                            >
                                <FormSelectOption value="bug-fix" label="Bug Fix" />
                                <FormSelectOption value="feature" label="Feature" />
                                <FormSelectOption value="question" label="Question" />
                                <FormSelectOption value="help" label="Help" />
                                <FormSelectOption value="other" label="Other" />
                            </FormSelect>
                        </FormGroup>
                        <FormGroup label="Issue Source" isRequired fieldId="issueSource">
                            <FormSelect
                                id="issueSource"
                                value={newProject.issueSource}
                                onChange={(_e, v) =>
                                    setNewProject({
                                        ...newProject,
                                        issueSource: v,
                                    })
                                }
                            >
                                <FormSelectOption value="github" label="GitHub" />
                                <FormSelectOption value="jira" label="Jira" />
                            </FormSelect>
                        </FormGroup>
                        <FormGroup label="Issue Reference" isRequired fieldId="issueRef">
                            <TextInput
                                id="issueRef"
                                isRequired
                                placeholder="owner/repo#123"
                                value={newProject.issueRef}
                                onChange={(_e, v) =>
                                    setNewProject({
                                        ...newProject,
                                        issueRef: v,
                                    })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Repository" isRequired fieldId="repository">
                            <TextInput
                                id="repository"
                                isRequired
                                placeholder="owner/repo"
                                value={newProject.repository}
                                onChange={(_e, v) =>
                                    setNewProject({
                                        ...newProject,
                                        repository: v,
                                    })
                                }
                            />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button
                        variant="primary"
                        onClick={handleCreate}
                        isDisabled={
                            !newProject.name ||
                            !newProject.issueRef ||
                            !newProject.repository
                        }
                    >
                        Create
                    </Button>
                    <Button
                        variant="link"
                        onClick={() => setIsModalOpen(false)}
                    >
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Project"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this project and all its data? This will remove all tasks,
                activity, thread entries, and workspace files. This cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}
