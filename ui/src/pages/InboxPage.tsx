import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
import {
    Alert,
    Button,
    Content,
    EmptyState,
    EmptyStateBody,
    ExpandableSection,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Pagination,
    Split,
    SplitItem,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import ExternalLinkAltIcon from "@patternfly/react-icons/dist/esm/icons/external-link-alt-icon";
import {
    type InboxItem,
    fetchInboxItems,
    fetchInboxItem,
    completeInboxItem,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { FromNow } from "../components/FromNow";
import { DynamicFormRenderer } from "../components/DynamicFormRenderer";

export function InboxPage() {
    const [items, setItems] = useState<InboxItem[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    // Detail modal state
    const [selectedItem, setSelectedItem] = useState<InboxItem | null>(null);
    const [formValues, setFormValues] = useState<Record<string, unknown>>({});
    const [formErrors, setFormErrors] = useState<Record<string, string>>({});
    const [submitting, setSubmitting] = useState(false);
    const [submitError, setSubmitError] = useState<string | null>(null);

    const loadData = useCallback(() => {
        setLoading(true);
        fetchInboxItems(page, perPage)
            .then((results) => {
                setItems(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage]);

    useEffect(() => { loadData(); }, [loadData]);

    // SSE: auto-refresh on inbox changes
    useEffect(() => {
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "inbox-updated" || event.type === "task-updated") {
                loadData();
            }
        });
        return unsubscribe;
    }, [loadData]);

    const openDetail = (item: InboxItem) => {
        // Fetch full detail (may include enriched data)
        fetchInboxItem(item.id)
            .then((detail) => {
                setSelectedItem(detail);
                setFormValues(buildDefaultValues(detail));
                setFormErrors({});
                setSubmitError(null);
            })
            .catch(console.error);
    };

    const closeDetail = () => {
        setSelectedItem(null);
        setFormValues({});
        setFormErrors({});
        setSubmitError(null);
    };

    const validateForm = (): boolean => {
        const errors: Record<string, string> = {};

        if (!selectedItem?.outputSchema) {
            const response = formValues["response"];
            if (!response || (typeof response === "string" && response.trim() === "")) {
                errors["response"] = "Response is required";
            }
        } else {
            for (const field of selectedItem.outputSchema.fields) {
                if (field.required) {
                    const value = formValues[field.name];
                    if (value === undefined || value === null || value === "") {
                        errors[field.name] = `${field.label} is required`;
                    }
                }
            }
        }

        setFormErrors(errors);
        return Object.keys(errors).length === 0;
    };

    const handleSubmit = () => {
        if (!selectedItem) return;
        if (!validateForm()) return;

        setSubmitting(true);
        setSubmitError(null);

        completeInboxItem(selectedItem.id, formValues)
            .then(() => {
                closeDetail();
                loadData();
            })
            .catch((err) => {
                setSubmitError(err.message || "Failed to submit response");
            })
            .finally(() => setSubmitting(false));
    };

    const getTitle = (item: InboxItem): string => {
        return item.humanContext?.title || item.actionType;
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Inbox
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadData}>
                            <SyncAltIcon />
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

            <div>
                {loading ? (
                    <EmptyState>
                        <EmptyStateBody>Loading inbox...</EmptyStateBody>
                    </EmptyState>
                ) : items.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            No tasks need your attention. When AI agents need your input,
                            tasks will appear here.
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Inbox items" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Title</Th>
                                <Th>Project</Th>
                                <Th>Action Type</Th>
                                <Th>Created</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {items.map((item) => (
                                <Tr
                                    key={item.id}
                                    isClickable
                                    onRowClick={() => openDetail(item)}
                                >
                                    <Td>
                                        <strong>{getTitle(item)}</strong>
                                    </Td>
                                    <Td>
                                        <Link
                                            to={`/projects/${item.projectId}`}
                                            onClick={(e) => e.stopPropagation()}
                                        >
                                            {item.projectName || `Project #${item.projectId}`}
                                        </Link>
                                    </Td>
                                    <Td>
                                        <Label isCompact color="orange">
                                            {item.actionType}
                                        </Label>
                                    </Td>
                                    <Td>
                                        <FromNow date={item.createdOn} />
                                    </Td>
                                    <Td>
                                        <Button
                                            variant="primary"
                                            size="sm"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                openDetail(item);
                                            }}
                                        >
                                            Respond
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            {/* Detail / Response Modal */}
            {selectedItem && (
                <Modal
                    isOpen
                    onClose={closeDetail}
                    variant="large"
                >
                    <ModalHeader title={getTitle(selectedItem)} />
                    <ModalBody>
                        {/* Context section */}
                        {selectedItem.humanContext?.description && (
                            <Content style={{ marginBottom: "16px" }}>
                                <p>{selectedItem.humanContext.description}</p>
                            </Content>
                        )}

                        {/* Reference links */}
                        {selectedItem.humanContext?.references &&
                            selectedItem.humanContext.references.length > 0 && (
                            <div style={{ marginBottom: "16px" }}>
                                <Split hasGutter>
                                    {selectedItem.humanContext.references.map((ref, i) => (
                                        <SplitItem key={i}>
                                            <Button
                                                variant="link"
                                                isInline
                                                component="a"
                                                href={ref.url}
                                                target="_blank"
                                                icon={<ExternalLinkAltIcon />}
                                                iconPosition="end"
                                            >
                                                {ref.label}
                                            </Button>
                                        </SplitItem>
                                    ))}
                                </Split>
                            </div>
                        )}

                        {/* Project info */}
                        <div style={{ marginBottom: "16px" }}>
                            <Split hasGutter>
                                <SplitItem>
                                    <strong>Project:</strong>{" "}
                                    <Link to={`/projects/${selectedItem.projectId}`}>
                                        {selectedItem.projectName ||
                                            `Project #${selectedItem.projectId}`}
                                    </Link>
                                </SplitItem>
                                <SplitItem>
                                    <strong>Action Type:</strong>{" "}
                                    <Label isCompact color="orange">
                                        {selectedItem.actionType}
                                    </Label>
                                </SplitItem>
                            </Split>
                        </div>

                        {/* Manager input (collapsible) */}
                        {selectedItem.input && (
                            <ExpandableSection
                                toggleText="Manager Input"
                                style={{ marginBottom: "16px" }}
                            >
                                <Content>
                                    <pre style={{
                                        whiteSpace: "pre-wrap",
                                        wordBreak: "break-word",
                                        fontSize: "0.9em",
                                        background: "var(--pf-t--global--background--color--secondary--default)",
                                        padding: "12px",
                                        borderRadius: "4px",
                                    }}>
                                        {selectedItem.input}
                                    </pre>
                                </Content>
                            </ExpandableSection>
                        )}

                        {/* Response form */}
                        <Title headingLevel="h3" size="md" style={{ marginBottom: "8px" }}>
                            Your Response
                        </Title>
                        <DynamicFormRenderer
                            schema={selectedItem.outputSchema}
                            values={formValues}
                            onChange={setFormValues}
                            errors={formErrors}
                        />

                        {submitError && (
                            <Alert
                                variant="danger"
                                title="Submission failed"
                                isInline
                                style={{ marginTop: "16px" }}
                            >
                                {submitError}
                            </Alert>
                        )}
                    </ModalBody>
                    <ModalFooter>
                        <Button
                            variant="primary"
                            onClick={handleSubmit}
                            isLoading={submitting}
                            isDisabled={submitting}
                        >
                            Submit Response
                        </Button>
                        <Button variant="link" onClick={closeDetail} isDisabled={submitting}>
                            Cancel
                        </Button>
                    </ModalFooter>
                </Modal>
            )}
        </PageSection>
    );
}

function buildDefaultValues(item: InboxItem): Record<string, unknown> {
    const values: Record<string, unknown> = {};
    if (item.outputSchema?.fields) {
        for (const field of item.outputSchema.fields) {
            if (field.defaultValue !== undefined && field.defaultValue !== null) {
                values[field.name] = field.defaultValue;
            } else if (field.type === "boolean") {
                values[field.name] = false;
            }
        }
    }
    return values;
}
