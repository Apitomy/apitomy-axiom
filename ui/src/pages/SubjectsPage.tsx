import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Form,
    FormGroup,
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
    type Subject,
    type NewSubject,
    fetchSubjects,
    createSubject,
    deleteSubject,
} from "../config/api";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "subject-filter-name" },
    { value: "labels", label: "Labels", testId: "subject-filter-labels" },
];

export function SubjectsPage() {
    const navigate = useNavigate();
    const [subjects, setSubjects] = useState<Subject[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [showInactive, setShowInactive] = useState(
        () => localStorage.getItem("axiom.subjects.showInactive") !== "false"
    );

    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newSubject, setNewSubject] = useState<NewSubject>({
        name: "",
        type: "other",
        ref: "",
    });

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const filterActive = showInactive ? undefined : true;
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0 || !showInactive;

    const loadSubjects = useCallback(() => {
        setLoading(true);
        fetchSubjects(
            page, perPage,
            filterName || undefined,
            filterActive,
            filterLabels || undefined
        )
            .then((results) => {
                setSubjects(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, filterActive, filterLabels]);

    useEffect(() => {
        loadSubjects();
    }, [loadSubjects]);

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
            deleteSubject(deleteTarget).then(loadSubjects).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleCreate = () => {
        createSubject(newSubject)
            .then(() => {
                setIsModalOpen(false);
                setNewSubject({
                    name: "",
                    type: "other",
                    ref: "",
                });
                loadSubjects();
            })
            .catch(console.error);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg">Subjects</Title>

            <Toolbar style={{ marginTop: "16px" }}>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput
                            filterTypes={FILTER_TYPES}
                            onAddCriteria={onAddFilterCriteria} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadSubjects}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem>
                        <Tooltip content={showInactive ? "Hide inactive subjects" : "Show inactive subjects"}>
                            <Button variant="control" aria-label="Toggle inactive subjects"
                                onClick={() => {
                                    const next = !showInactive;
                                    setShowInactive(next);
                                    localStorage.setItem("axiom.subjects.showInactive", String(next));
                                    setPage(1);
                                }}>
                                {showInactive ? <EyeIcon /> : <EyeSlashIcon />}
                            </Button>
                        </Tooltip>
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button
                            variant="primary"
                            icon={<PlusCircleIcon />}
                            onClick={() => setIsModalOpen(true)}
                        >
                            Create Subject
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
                        <EmptyStateBody>Loading subjects...</EmptyStateBody>
                    </EmptyState>
                ) : subjects.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {filters.length > 0
                                ? "No subjects match the current filters."
                                : !showInactive
                                    ? "No active subjects. Toggle the eye icon to show inactive ones."
                                    : "No subjects yet. Create one or wait for events from a monitored repository."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Subjects" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Status</Th>
                                <Th>Reference</Th>
                                <Th>Labels</Th>
                                <Th>Updated</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {subjects.map((subject) => (
                                <Tr
                                    key={subject.id}
                                    isClickable
                                    onRowClick={() => navigate(`/subjects/${subject.id}`)}
                                >
                                    <Td>
                                        {subject.refSource === "github" && <GithubIcon style={{ marginRight: 6 }} />}
                                        {subject.refSource === "jira" && <JiraIcon style={{ marginRight: 6 }} />}
                                        {subject.type === "issue" && <BugIcon style={{ marginRight: 6 }} />}
                                        {subject.type === "pull-request" && <CodeBranchIcon style={{ marginRight: 6 }} />}
                                        {subject.name}
                                    </Td>
                                    <Td>
                                        <Label isCompact={true} color={subject.active ? "green" : "grey"}>
                                            {subject.active ? "Active" : "Inactive"}
                                        </Label>
                                    </Td>
                                    <Td>{subject.ref}</Td>
                                    <Td>
                                        {subject.labels?.map((label) => (
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
                                    <Td>{new Date(subject.updatedOn).toLocaleString()}</Td>
                                    <Td>
                                        {!subject.active && (
                                            <Button variant="plain" size="sm" style={{ padding: 0 }}
                                                onClick={(e) => handleDelete(e, subject.id)}>
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

            {/* Create Subject Modal */}
            <Modal
                isOpen={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                variant="medium"
            >
                <ModalHeader title="Create Subject" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput
                                id="name"
                                isRequired
                                value={newSubject.name}
                                onChange={(_e, v) =>
                                    setNewSubject({ ...newSubject, name: v })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Body" fieldId="body">
                            <TextArea
                                id="body"
                                value={newSubject.body || ""}
                                onChange={(_e, v) =>
                                    setNewSubject({
                                        ...newSubject,
                                        body: v,
                                    })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Type" isRequired fieldId="type">
                            <TextInput
                                id="type"
                                isRequired
                                list="type-suggestions"
                                placeholder="e.g. bug-fix, feature, cve"
                                value={newSubject.type}
                                onChange={(_e, v) =>
                                    setNewSubject({ ...newSubject, type: v })
                                }
                            />
                            <datalist id="type-suggestions">
                                <option value="bug-fix" />
                                <option value="feature" />
                                <option value="issue" />
                                <option value="pull-request" />
                                <option value="cve" />
                                <option value="question" />
                                <option value="help" />
                                <option value="other" />
                            </datalist>
                        </FormGroup>
                        <FormGroup label="Reference" isRequired fieldId="ref">
                            <TextInput
                                id="ref"
                                isRequired
                                placeholder="owner/repo#123 or CVE-2024-12345"
                                value={newSubject.ref}
                                onChange={(_e, v) =>
                                    setNewSubject({
                                        ...newSubject,
                                        ref: v,
                                    })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Source" fieldId="refSource">
                            <TextInput
                                id="refSource"
                                placeholder="e.g. github, jira"
                                value={newSubject.refSource || ""}
                                onChange={(_e, v) =>
                                    setNewSubject({
                                        ...newSubject,
                                        refSource: v || undefined,
                                    })
                                }
                            />
                        </FormGroup>
                        <FormGroup label="Repository" fieldId="repository">
                            <TextInput
                                id="repository"
                                placeholder="owner/repo"
                                value={newSubject.repository || ""}
                                onChange={(_e, v) =>
                                    setNewSubject({
                                        ...newSubject,
                                        repository: v || undefined,
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
                            !newSubject.name ||
                            !newSubject.ref
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

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Subject"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this subject and all its data? This will remove all tasks,
                activity, thread entries, and workspace files. This cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}
