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
    type ScheduledJob,
    type NewScheduledJob,
    fetchScheduledJobs,
    createScheduledJob,
    deleteScheduledJob,
} from "../config/api";
import { FromNow } from "../components/FromNow";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function ScheduledJobsPage() {
    const navigate = useNavigate();
    const [jobs, setJobs] = useState<ScheduledJob[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newSchedule, setNewSchedule] = useState("daily");
    const [newExecutionMode, setNewExecutionMode] = useState("actor");

    const loadData = useCallback(() => {
        setLoading(true);
        fetchScheduledJobs()
            .then((data) => setJobs(data.sort((a, b) => a.name.localeCompare(b.name))))
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
            deleteScheduledJob(deleteTarget).then(loadData).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleCreate = () => {
        const data: NewScheduledJob = {
            name: newName,
            schedule: newSchedule,
            executionMode: newExecutionMode,
            enabled: false,
        };
        createScheduledJob(data)
            .then((job) => {
                setIsModalOpen(false);
                navigate(`/scheduled-jobs/${job.id}`);
            })
            .catch(console.error);
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Scheduled Jobs</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />}
                        onClick={() => { setNewName(""); setNewSchedule("daily"); setNewExecutionMode("actor"); setIsModalOpen(true); }}>
                        Create Scheduled Job
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : jobs.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No scheduled jobs configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Scheduled Jobs" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Schedule</Th>
                                <Th>Execution Mode</Th>
                                <Th>Last Run</Th>
                                <Th>Next Run</Th>
                                <Th>Enabled</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {jobs.map((job) => (
                                <Tr key={job.id} isClickable
                                    onRowClick={() => navigate(`/scheduled-jobs/${job.id}`)}>
                                    <Td>{job.name}</Td>
                                    <Td>
                                        <Label isCompact>{job.schedule}</Label>
                                        {job.scheduleTime && (
                                            <span className="axiom-text-subtle" style={{ marginLeft: "4px", fontSize: "12px" }}>
                                                at {job.scheduleTime}
                                            </span>
                                        )}
                                    </Td>
                                    <Td>
                                        <Label isCompact color={job.executionMode === "actor" ? "blue" : "purple"}>
                                            {job.executionMode}
                                        </Label>
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {job.lastRunAt
                                            ? <FromNow date={job.lastRunAt} />
                                            : <span className="axiom-text-subtle">Never</span>}
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {job.nextRunAt
                                            ? <FromNow date={job.nextRunAt} />
                                            : <span className="axiom-text-subtle">--</span>}
                                    </Td>
                                    <Td>
                                        <Label isCompact color={job.enabled ? "green" : "grey"}>
                                            {job.enabled ? "Yes" : "No"}
                                        </Label>
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, job.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Scheduled Job"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this scheduled job and all its run history?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title="Create Scheduled Job" />
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
                        <FormGroup label="Execution Mode" isRequired fieldId="executionMode">
                            <FormSelect id="executionMode" value={newExecutionMode}
                                onChange={(_e, v) => setNewExecutionMode(v)}>
                                <FormSelectOption value="actor" label="Actor" />
                                <FormSelectOption value="script" label="Script" />
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
