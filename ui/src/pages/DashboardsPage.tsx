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
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import StarIcon from "@patternfly/react-icons/dist/esm/icons/star-icon";
import OutlinedStarIcon from "@patternfly/react-icons/dist/esm/icons/outlined-star-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Dashboard,
    type NewDashboard,
    fetchDashboards,
    createDashboard,
    deleteDashboard,
    updateDashboard,
} from "../config/api";
import { ColoredLabel } from "../components/ColoredLabel";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { LabelInput } from "../components/LabelInput";

export function DashboardsPage() {
    const navigate = useNavigate();
    const [dashboards, setDashboards] = useState<Dashboard[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newDescription, setNewDescription] = useState("");
    const [newLabels, setNewLabels] = useState<string[]>([]);

    const load = useCallback(() => {
        setLoading(true);
        fetchDashboards().then(setDashboards).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        const data: NewDashboard = {
            name: newName,
            description: newDescription || undefined,
            labels: newLabels,
            isDefault: dashboards.length === 0,
            widgets: [],
        };
        createDashboard(data)
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                setNewDescription("");
                setNewLabels([]);
                navigate(`/dashboards/${created.id}`);
            })
            .catch(console.error);
    };

    const handleSetDefault = (e: React.MouseEvent, dashboard: Dashboard) => {
        e.stopPropagation();
        if (dashboard.isDefault) return;
        const data: NewDashboard = {
            name: dashboard.name,
            description: dashboard.description,
            labels: dashboard.labels,
            isDefault: true,
            widgets: dashboard.widgets,
        };
        updateDashboard(dashboard.id, data).then(load).catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteDashboard(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const formatDate = (dateStr: string) => {
        return new Date(dateStr).toLocaleDateString(undefined, {
            year: "numeric", month: "short", day: "numeric",
            hour: "2-digit", minute: "2-digit",
        });
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                  alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Dashboards</Title></FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={() => {
                        setNewName("");
                        setNewDescription("");
                        setNewLabels([]);
                        setIsCreateOpen(true);
                    }}>
                        Create Dashboard
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : dashboards.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            No dashboards yet. Create your first dashboard to get started.
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Dashboards" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Labels</Th>
                                <Th>Widgets</Th>
                                <Th>Last Updated</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {dashboards.map((d) => (
                                <Tr key={d.id} isClickable
                                    onRowClick={() => navigate(`/dashboards/${d.id}`)}>
                                    <Td>
                                        {d.name}
                                        {d.isDefault && (
                                            <Label isCompact color="blue"
                                                   style={{ marginLeft: "8px" }}>
                                                Default
                                            </Label>
                                        )}
                                    </Td>
                                    <Td>
                                        {d.labels.map(l => (
                                            <ColoredLabel key={l} isCompact
                                                   style={{ marginRight: "4px" }}>{l}</ColoredLabel>
                                        ))}
                                        {d.labels.length === 0 && "—"}
                                    </Td>
                                    <Td>{d.widgets.length}</Td>
                                    <Td>{formatDate(d.updatedOn)}</Td>
                                    <Td>
                                        <Flex gap={{ default: "gapSm" }}
                                              flexWrap={{ default: "nowrap" }}>
                                            <FlexItem>
                                                <Button variant="plain" size="sm"
                                                        style={{ padding: 0 }}
                                                        aria-label={d.isDefault ? "Default dashboard" : "Set as default"}
                                                        isDisabled={d.isDefault}
                                                        onClick={(e) => handleSetDefault(e, d)}>
                                                    {d.isDefault ? <StarIcon color="var(--pf-t--global--color--status--info--default)" /> : <OutlinedStarIcon />}
                                                </Button>
                                            </FlexItem>
                                            <FlexItem>
                                                <Button variant="plain" size="sm"
                                                        style={{ padding: 0 }}
                                                        onClick={(e) => handleDelete(e, d.id)}>
                                                    <TrashIcon />
                                                </Button>
                                            </FlexItem>
                                        </Flex>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Dashboard"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this dashboard and all its widgets?
            </ConfirmDeleteModal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="small">
                <ModalHeader title="Create Dashboard" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={newName}
                                       onChange={(_e, v) => setNewName(v)}
                                       onKeyDown={(e) => {
                                           if (e.key === "Enter" && newName.trim()) {
                                               e.preventDefault();
                                               handleCreate();
                                           }
                                       }} />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextArea id="description" value={newDescription}
                                      onChange={(_e, v) => setNewDescription(v)} />
                        </FormGroup>
                        <FormGroup label="Labels" fieldId="labels">
                            <LabelInput labels={newLabels}
                                onChange={(labels) => setNewLabels(labels)} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate}
                            isDisabled={!newName.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsCreateOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
