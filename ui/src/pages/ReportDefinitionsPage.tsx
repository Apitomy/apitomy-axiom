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
    FormSelectOption,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ReportDefinition,
    fetchReportDefinitions,
    createReportDefinition,
    deleteReportDefinition,
} from "../config/api";

export function ReportDefinitionsPage() {
    const navigate = useNavigate();
    const [definitions, setDefinitions] = useState<ReportDefinition[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newSchedule, setNewSchedule] = useState("daily");

    const loadData = useCallback(() => {
        setLoading(true);
        fetchReportDefinitions()
            .then(setDefinitions)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadData(); }, [loadData]);

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteReportDefinition(deleteTarget).then(loadData).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleCreate = () => {
        createReportDefinition({
            name: newName,
            schedule: newSchedule,
            timeWindow: "last-24h",
            promptTemplate: "Summarize recent activity across all repositories.",
            enabled: false,
        })
            .then((def) => {
                setIsModalOpen(false);
                navigate(`/report-definitions/${def.id}`);
            })
            .catch(console.error);
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Report Definitions</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />}
                        onClick={() => { setNewName(""); setNewSchedule("daily"); setIsModalOpen(true); }}>
                        Create Definition
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : definitions.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No report definitions configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Report Definitions" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Schedule</Th>
                                <Th>Time Window</Th>
                                <Th>Enabled</Th>
                                <Th>Last Run</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {definitions.map((def) => (
                                <Tr key={def.id} isClickable
                                    onRowClick={() => navigate(`/report-definitions/${def.id}`)}>
                                    <Td>{def.name}</Td>
                                    <Td>
                                        <Label isCompact>{def.schedule}</Label>
                                        {def.scheduleTime && (
                                            <span style={{ marginLeft: "4px", fontSize: "12px", color: "#6a6e73" }}>
                                                at {def.scheduleTime}
                                            </span>
                                        )}
                                    </Td>
                                    <Td><Label isCompact>{def.timeWindow}</Label></Td>
                                    <Td>
                                        <Label isCompact color={def.enabled ? "green" : "grey"}>
                                            {def.enabled ? "Yes" : "No"}
                                        </Label>
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {def.lastRunAt
                                            ? new Date(def.lastRunAt).toLocaleString()
                                            : "Never"}
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, def.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <Modal isOpen={deleteTarget !== null} onClose={() => setDeleteTarget(null)} variant="small">
                <ModalHeader title="Delete Report Definition" />
                <ModalBody>
                    Delete this report definition and all its generated reports?
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={confirmDelete}>
                        Delete
                    </Button>
                    <Button variant="link" onClick={() => setDeleteTarget(null)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title="Create Report Definition" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={newName}
                                onChange={(_e, v) => setNewName(v)} />
                        </FormGroup>
                        <FormGroup label="Schedule" isRequired fieldId="schedule">
                            <FormSelect id="schedule" value={newSchedule}
                                onChange={(_e, v) => setNewSchedule(v)}>
                                <FormSelectOption value="none" label="Not Scheduled (ad hoc only)" />
                                <FormSelectOption value="hourly" label="Hourly" />
                                <FormSelectOption value="daily" label="Daily" />
                                <FormSelectOption value="weekly" label="Weekly" />
                                <FormSelectOption value="monthly" label="Monthly" />
                            </FormSelect>
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate} isDisabled={!newName.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
