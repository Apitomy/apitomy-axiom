import { useState, useEffect, useCallback } from "react";
import { Navigate, useNavigate } from "react-router-dom";
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
    Radio,
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
import { useErrorToasts } from "../components/dashboards/useErrorToasts";
import {
    DASHBOARD_TEMPLATES,
    buildDashboardFromTemplate,
    getTemplate,
} from "../components/dashboards/templates";

export function DashboardsPage() {
    const navigate = useNavigate();
    const [dashboards, setDashboards] = useState<Dashboard[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newDescription, setNewDescription] = useState("");
    const [newLabels, setNewLabels] = useState<string[]>([]);
    const [templateId, setTemplateId] = useState<string | null>(null);
    const [templateName, setTemplateName] = useState("");
    const [templateLabels, setTemplateLabels] = useState<string[]>([]);
    const { showError, toasts } = useErrorToasts();

    const openTemplate = (id: string) => {
        setTemplateId(id);
        setTemplateName(getTemplate(id)?.name ?? "");
        setTemplateLabels([]);
    };

    const handleCreateFromTemplate = () => {
        const template = templateId ? getTemplate(templateId) : undefined;
        if (!template || !templateName.trim()) return;
        const data = buildDashboardFromTemplate(template, templateName.trim(), templateLabels,
            dashboards.length === 0);
        createDashboard(data)
            .then((created) => {
                setTemplateId(null);
                navigate(`/dashboards/${created.id}`);
            })
            .catch((e) => showError("Failed to create dashboard", e));
    };

    const templateButtons = (
        <Flex gap={{ default: "gapSm" }} justifyContent={{ default: "justifyContentCenter" }}>
            {DASHBOARD_TEMPLATES.map((t) => (
                <FlexItem key={t.id}>
                    <Button variant="secondary" onClick={() => openTemplate(t.id)}
                            aria-label={`Create from template ${t.name}`}>
                        {t.name}
                    </Button>
                </FlexItem>
            ))}
        </Flex>
    );

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
            tabs: [{ id: crypto.randomUUID(), name: "Default", widgets: [] }],
        };
        createDashboard(data)
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                setNewDescription("");
                setNewLabels([]);
                navigate(`/dashboards/${created.id}`);
            })
            .catch((e) => showError("Failed to create dashboard", e));
    };

    const handleSetDefault = (e: React.MouseEvent, dashboard: Dashboard) => {
        e.stopPropagation();
        if (dashboard.isDefault) return;
        const data: NewDashboard = {
            name: dashboard.name,
            description: dashboard.description,
            labels: dashboard.labels,
            isDefault: true,
            tabs: dashboard.tabs,
        };
        updateDashboard(dashboard.id, data).then(load)
            .catch((e) => showError("Failed to set default dashboard", e));
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteDashboard(deleteTarget).then(load)
                .catch((e) => showError("Failed to delete dashboard", e));
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
            {toasts}
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                  alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Dashboards</Title></FlexItem>
                <FlexItem>
                    <Button variant="secondary" style={{ marginRight: "8px" }}
                            onClick={() => openTemplate(DASHBOARD_TEMPLATES[0].id)}>
                        Create from Template
                    </Button>
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
                            No dashboards yet. Create your first dashboard to get started, or start
                            from a template:
                        </EmptyStateBody>
                        <div style={{ marginTop: "12px" }}>{templateButtons}</div>
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
                                    <Td>{d.tabs.reduce((n, t) => n + t.widgets.length, 0)}</Td>
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
                                                        aria-label={`Delete dashboard ${d.name}`}
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

            <Modal isOpen={templateId !== null} onClose={() => setTemplateId(null)} variant="small">
                <ModalHeader title="Create from Template" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Template" fieldId="template" role="radiogroup">
                            {DASHBOARD_TEMPLATES.map((t) => (
                                <Radio key={t.id} id={`template-${t.id}`} name="template"
                                       label={t.name} description={t.description}
                                       isChecked={templateId === t.id}
                                       onChange={() => {
                                           const prev = templateId ? getTemplate(templateId)?.name : undefined;
                                           setTemplateId(t.id);
                                           if (!templateName.trim() || templateName === prev) {
                                               setTemplateName(t.name);
                                           }
                                       }} />
                            ))}
                        </FormGroup>
                        <FormGroup label="Name" isRequired fieldId="template-name">
                            <TextInput id="template-name" isRequired value={templateName}
                                       onChange={(_e, v) => setTemplateName(v)} />
                        </FormGroup>
                        <FormGroup label="Labels" fieldId="template-labels">
                            <LabelInput labels={templateLabels}
                                onChange={(labels) => setTemplateLabels(labels)} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreateFromTemplate}
                            isDisabled={!templateName.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setTemplateId(null)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}

/**
 * Entry point for `/dashboards`: opens the default dashboard when one exists, otherwise shows the list
 * (`/dashboards/all`).
 */
export function DashboardHomePage() {
    const [target, setTarget] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        fetchDashboards()
            .then((all) => {
                if (cancelled) return;
                const def = all.find((d) => d.isDefault);
                setTarget(def ? `/dashboards/${def.id}` : "/dashboards/all");
            })
            .catch((e) => {
                console.error(e);
                if (!cancelled) setTarget("/dashboards/all");
            });
        return () => { cancelled = true; };
    }, []);

    if (target) return <Navigate to={target} replace />;
    return (
        <PageSection>
            <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
        </PageSection>
    );
}
