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
import { ColoredLabel } from "../components/ColoredLabel";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { LabelInput } from "../components/LabelInput";
import {
    type Subscription,
    type NewSubscription,
    fetchSubscriptions,
    createSubscription,
    deleteSubscription,
} from "../config/api";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "subscription-filter-name" },
];

export function SubscriptionsPage() {
    const navigate = useNavigate();
    const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [form, setForm] = useState<NewSubscription>({
        name: "", description: "", enabled: true, labels: [],
    });

    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const isFiltered = filters.length > 0;

    const load = useCallback(() => {
        setLoading(true);
        fetchSubscriptions(page, perPage, filterName || undefined)
            .then((results) => {
                setSubscriptions(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName]);

    useEffect(() => { load(); }, [load]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const withoutSame = filters.filter((f) => f.filterBy.value !== criteria.filterBy.value);
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

    const openCreate = () => {
        setForm({ name: "", description: "", enabled: true, labels: [] });
        setIsModalOpen(true);
    };

    const handleSave = () => {
        createSubscription(form)
            .then(() => { setIsModalOpen(false); load(); })
            .catch(console.error);
    };

    const handleDelete = (id: number) => {
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteSubscription(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const truncate = (text: string | undefined, maxLen = 60): string => {
        if (!text) return "";
        return text.length > maxLen ? text.substring(0, maxLen) + "..." : text;
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Subscriptions</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>
                        Add Subscription
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
                ) : subscriptions.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No subscriptions match the current filters."
                                : "No subscriptions configured."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Subscriptions" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Enabled</Th>
                                <Th>Labels</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {subscriptions.map((s) => (
                                <Tr key={s.id} isClickable onRowClick={() => navigate(`/subscriptions/${s.id}`)}>
                                    <Td><strong>{s.name}</strong></Td>
                                    <Td>{truncate(s.description)}</Td>
                                    <Td><BooleanStatusIcon value={s.enabled} /></Td>
                                    <Td>
                                        <div style={{ display: "flex", flexWrap: "wrap", gap: "4px" }}>
                                            {(s.labels || []).map((label) => (
                                                <ColoredLabel key={label} isCompact>{label}</ColoredLabel>
                                            ))}
                                        </div>
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => { e.stopPropagation(); handleDelete(s.id); }}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Subscription"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this subscription?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title="Add Subscription" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={form.name}
                                onChange={(_e, v) => setForm({ ...form, name: v })}
                                placeholder="e.g. GitHub PR Events" />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextArea id="description" value={form.description || ""}
                                onChange={(_e, v) => setForm({ ...form, description: v })} />
                        </FormGroup>
                        <FormGroup fieldId="enabled">
                            <Switch id="enabled" label="Enabled"
                                isChecked={form.enabled}
                                onChange={(_e, v) => setForm({ ...form, enabled: v })} />
                        </FormGroup>
                        <FormGroup label="Labels" fieldId="labels">
                            <LabelInput
                                labels={form.labels || []}
                                onChange={(labels) => setForm({ ...form, labels })} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave} isDisabled={!form.name}>
                        Save
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
