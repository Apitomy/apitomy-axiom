import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSelect,
    HelperText,
    HelperTextItem,
    FormSelectOption,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Pagination,
    Switch,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { BooleanStatusIcon } from "../components/BooleanStatusIcon";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import {
    type Connection,
    type NewConnection,
    type Secret,
    fetchConnections,
    createConnection,
    deleteConnection,
    fetchSecrets,
} from "../config/api";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "connection-filter-name" },
    { value: "type", label: "Type", testId: "connection-filter-type" },
];

const SLUG_PATTERN = /^[a-z0-9-]*$/;

export function ConnectionsPage() {
    const navigate = useNavigate();
    const [connections, setConnections] = useState<Connection[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [form, setForm] = useState<NewConnection>({
        id: "", name: "", sourceType: "github", enabled: true, baseUrl: "",
    });

    const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

    const [secrets, setSecrets] = useState<Secret[]>([]);

    // Configuration text areas
    const [reposText, setReposText] = useState("");
    const [projectsText, setProjectsText] = useState("");

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const filterType = filters.find((f) => f.filterBy.value === "type")?.filterValue;
    const isFiltered = filters.length > 0;

    const load = useCallback(() => {
        setLoading(true);
        fetchConnections(page, perPage, filterName || undefined, filterType || undefined)
            .then((results) => {
                setConnections(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, filterType]);

    useEffect(() => { load(); }, [load]);
    useEffect(() => { fetchSecrets().then(setSecrets).catch(console.error); }, []);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        // Name and type are single-value filters
        const withoutSame = updated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
        withoutSame.push(criteria);
        setFilters(withoutSame);
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

    const addTypeFilter = (type: string) => {
        const typeFilterType = FILTER_TYPES.find((t) => t.value === "type")!;
        const withoutType = filters.filter((f) => f.filterBy.value !== "type");
        withoutType.push({ filterBy: typeFilterType, filterValue: type });
        setFilters(withoutType);
        setPage(1);
    };

    const openCreate = () => {
        setForm({ id: "", name: "", sourceType: "github", enabled: true, baseUrl: "" });
        setReposText("");
        setProjectsText("");
        setIsModalOpen(true);
    };

    const buildConfiguration = (): Record<string, unknown> => {
        if (form.sourceType === "github") {
            return { repositories: reposText.split("\n").map((l) => l.trim()).filter(Boolean) };
        } else if (form.sourceType === "jira") {
            return { projects: projectsText.split("\n").map((l) => l.trim()).filter(Boolean) };
        }
        return {};
    };

    const handleSave = () => {
        const data: NewConnection = { ...form, configuration: buildConfiguration() };
        createConnection(data)
            .then(() => { setIsModalOpen(false); load(); })
            .catch(console.error);
    };

    const handleDelete = (id: string) => {
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteConnection(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleSourceTypeChange = (newType: string) => {
        setForm({ ...form, sourceType: newType, baseUrl: "" });
        setReposText("");
        setProjectsText("");
    };

    const isSlugValid = (slug: string): boolean => {
        return SLUG_PATTERN.test(slug) && slug.length <= 63;
    };

    const isFormValid = (): boolean => {
        if (!form.id || !form.name || !form.sourceType || !form.baseUrl) return false;
        if (!isSlugValid(form.id)) return false;
        return true;
    };

    const truncateUrl = (url: string, maxLen = 50): string => {
        return url.length > maxLen ? url.substring(0, maxLen) + "..." : url;
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Connections</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>
                        Add Connection
                    </Button>
                </FlexItem>
            </Flex>

            <Toolbar style={{ marginTop: "16px" }}>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput
                            filterTypes={FILTER_TYPES}
                            onAddCriteria={onAddFilterCriteria} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={load}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            perPage={perPage}
                            page={page}
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
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : connections.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No connections match the current filters."
                                : "No connections configured."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Connections" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>ID</Th>
                                <Th>Name</Th>
                                <Th>Type</Th>
                                <Th>Base URL</Th>
                                <Th>Enabled</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {connections.map((c) => (
                                <Tr key={c.id} isClickable onRowClick={() => navigate(`/connections/${c.id}`)}>
                                    <Td><strong>{c.id}</strong></Td>
                                    <Td>{c.name}</Td>
                                    <Td>
                                        <Label isCompact
                                            color={c.sourceType === "github" ? "blue" : c.sourceType === "jira" ? "green" : undefined}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                addTypeFilter(c.sourceType);
                                            }}>
                                            {c.sourceType}
                                        </Label>
                                    </Td>
                                    <Td><code>{truncateUrl(c.baseUrl)}</code></Td>
                                    <Td><BooleanStatusIcon value={c.enabled} /></Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => { e.stopPropagation(); handleDelete(c.id); }}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Connection"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this connection?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title="Add Connection" />
                <ModalBody>
                    <Form>
                        <FormGroup label="ID (Slug)" isRequired fieldId="id">
                            <TextInput id="id" isRequired value={form.id}
                                onChange={(_e, v) => setForm({ ...form, id: v })}
                                placeholder="my-connection" />
                            {form.id && !isSlugValid(form.id) ? (
                                <HelperText>
                                    <HelperTextItem variant="error">
                                        Must contain only lowercase letters, numbers, and dashes (max 63 characters)
                                    </HelperTextItem>
                                </HelperText>
                            ) : (
                                <HelperText>
                                    <HelperTextItem>
                                        Unique identifier (lowercase letters, numbers, dashes)
                                    </HelperTextItem>
                                </HelperText>
                            )}
                        </FormGroup>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={form.name}
                                onChange={(_e, v) => setForm({ ...form, name: v })}
                                placeholder="e.g. My GitHub Connection" />
                        </FormGroup>
                        <FormGroup label="Source Type" isRequired fieldId="sourceType">
                            <FormSelect id="sourceType" value={form.sourceType}
                                onChange={(_e, v) => handleSourceTypeChange(v)}>
                                <FormSelectOption value="github" label="GitHub" />
                                <FormSelectOption value="jira" label="Jira" />
                            </FormSelect>
                        </FormGroup>
                        <FormGroup label="Base URL" isRequired fieldId="baseUrl">
                            <TextInput id="baseUrl" isRequired value={form.baseUrl}
                                onChange={(_e, v) => setForm({ ...form, baseUrl: v })}
                                placeholder={form.sourceType === "github"
                                    ? "https://api.github.com"
                                    : "https://your-org.atlassian.net"} />
                        </FormGroup>

                        {form.sourceType === "github" && (
                            <FormGroup label="Repositories" fieldId="repositories">
                                <TextArea id="repositories" value={reposText}
                                    onChange={(_e, v) => setReposText(v)}
                                    placeholder={"owner/repo\nowner/repo2"}
                                    rows={4} />
                                <HelperText><HelperTextItem>One owner/repo per line</HelperTextItem></HelperText>
                            </FormGroup>
                        )}

                        {form.sourceType === "jira" && (
                            <FormGroup label="Projects" fieldId="projects">
                                <TextArea id="projects" value={projectsText}
                                    onChange={(_e, v) => setProjectsText(v)}
                                    placeholder={"PROJ1\nPROJ2"}
                                    rows={4} />
                                <HelperText><HelperTextItem>One project key per line</HelperTextItem></HelperText>
                            </FormGroup>
                        )}

                        <FormGroup label="Authentication Secret" fieldId="secretName">
                            <FormSelect id="secretName"
                                value={form.secretName || ""}
                                onChange={(_e, v) => setForm({ ...form, secretName: v || undefined })}>
                                <FormSelectOption value="" label="None" />
                                {secrets.map((s) => (
                                    <FormSelectOption key={s.name} value={s.name} label={s.name} />
                                ))}
                            </FormSelect>
                        </FormGroup>
                        <FormGroup fieldId="enabled">
                            <Switch id="enabled" label="Enabled"
                                isChecked={form.enabled}
                                onChange={(_e, v) => setForm({ ...form, enabled: v })} />
                        </FormGroup>
                        <FormGroup label="Poll Interval (seconds)" fieldId="pollInterval">
                            <TextInput id="pollInterval" type="number"
                                value={form.pollInterval?.toString() || ""}
                                onChange={(_e, v) => setForm({ ...form, pollInterval: v ? parseInt(v) : undefined })}
                                placeholder="60" />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave} isDisabled={!isFormValid()}>
                        Save
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
