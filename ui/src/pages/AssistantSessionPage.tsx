import { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate, useSearchParams } from "react-router-dom";
import {
    Badge,
    PageSection,
    Button,
    TextInput,
    Flex,
    FlexItem,
    Label,
    Spinner,
    Tooltip,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    Alert,
} from "@patternfly/react-core";
import { Table, Thead, Tr, Th, Tbody, Td } from "@patternfly/react-table";
import ArrowLeftIcon from "@patternfly/react-icons/dist/esm/icons/arrow-left-icon";
import ExternalLinkAltIcon from "@patternfly/react-icons/dist/esm/icons/external-link-alt-icon";
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import ShieldAltIcon from "@patternfly/react-icons/dist/esm/icons/shield-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import { AssistantChatPanel, type SessionMode } from "../components/assistant/AssistantChatPanel";
import { AssistantGeneratedItems } from "../components/assistant/AssistantGeneratedItems";
import {
    fetchAssistantSession,
    deleteAssistantSession,
    applyAssistantSession,
    renameAssistantSession,
    fetchAutoApprovals,
    deleteAutoApproval,
    type AssistantSessionInfo,
    type AutoApprovalRule,
    type AssistantApplyResult,
} from "../config/api";

export function AssistantSessionPage() {
    const { sessionId } = useParams<{ sessionId: string }>();
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const isBreakout = searchParams.get("breakout") === "true";
    const [session, setSession] = useState<AssistantSessionInfo | null>(null);
    const [loading, setLoading] = useState(true);
    const [itemsRefresh, setItemsRefresh] = useState(0);
    const [isEndConfirmOpen, setIsEndConfirmOpen] = useState(false);
    const [applying, setApplying] = useState(false);
    const [applyResult, setApplyResult] = useState<AssistantApplyResult | null>(null);
    const [applyError, setApplyError] = useState<string | null>(null);
    const [sessionMode, setSessionMode] = useState<SessionMode>("normal");
    const [itemCount, setItemCount] = useState(0);
    const [isEditingName, setIsEditingName] = useState(false);
    const [editName, setEditName] = useState("");
    const [sessionModel, setSessionModel] = useState<string | null>(null);
    const [sessionCost, setSessionCost] = useState(0);
    const [sessionInputTokens, setSessionInputTokens] = useState(0);
    const [sessionOutputTokens, setSessionOutputTokens] = useState(0);
    const [autoApprovalCount, setAutoApprovalCount] = useState(0);
    const [autoApprovalRules, setAutoApprovalRules] = useState<AutoApprovalRule[]>([]);
    const [isAutoApprovalModalOpen, setIsAutoApprovalModalOpen] = useState(false);

    useEffect(() => {
        if (!sessionId) return;
        setLoading(true);
        fetchAssistantSession(sessionId)
            .then((s) => {
                setSession(s);
                if (isBreakout) {
                    document.title = `${s.name} — AI Assistant`;
                }
            })
            .catch((err) => {
                console.error("Failed to load session:", err);
                if (!isBreakout) navigate("/assistant");
            })
            .finally(() => setLoading(false));
        fetchAutoApprovals(sessionId)
            .then((rules) => setAutoApprovalCount(rules.length))
            .catch(console.error);
    }, [sessionId, navigate, isBreakout]);

    const handleNameSave = async () => {
        if (!sessionId || !editName.trim() || editName.trim() === session?.name) {
            setIsEditingName(false);
            return;
        }
        try {
            const updated = await renameAssistantSession(sessionId, editName.trim());
            setSession(updated);
            if (isBreakout) {
                document.title = `${updated.name} — AI Assistant`;
            }
        } catch (err) {
            console.error("Failed to rename session:", err);
        }
        setIsEditingName(false);
    };

    const handleItemsChanged = useCallback(() => {
        setItemsRefresh((n) => n + 1);
    }, []);

    const handleItemCountChanged = useCallback((count: number) => {
        setItemCount(count);
    }, []);

    const handleAutoApprovalCountChange = useCallback(() => {
        if (!sessionId) return;
        fetchAutoApprovals(sessionId)
            .then((rules) => setAutoApprovalCount(rules.length))
            .catch(console.error);
    }, [sessionId]);

    const openAutoApprovalModal = () => {
        if (!sessionId) return;
        fetchAutoApprovals(sessionId)
            .then((rules) => {
                setAutoApprovalRules(rules);
                setAutoApprovalCount(rules.length);
                setIsAutoApprovalModalOpen(true);
            })
            .catch(console.error);
    };

    const handleDeleteAutoApproval = async (ruleId: string) => {
        if (!sessionId) return;
        try {
            await deleteAutoApproval(sessionId, ruleId);
            const updated = autoApprovalRules.filter((r) => r.id !== ruleId);
            setAutoApprovalRules(updated);
            setAutoApprovalCount(updated.length);
        } catch (err) {
            console.error("Failed to delete auto-approval:", err);
        }
    };

    const handleEndSession = async () => {
        if (!sessionId) return;
        try {
            await deleteAssistantSession(sessionId);
            if (isBreakout) {
                window.close();
            } else {
                navigate("/assistant");
            }
        } catch (err) {
            console.error("Failed to end session:", err);
        }
    };

    const handleBreakout = () => {
        if (!sessionId) return;
        window.open(
            `/assistant/${sessionId}?breakout=true`,
            "_blank",
        );
        navigate("/assistant");
    };

    const handleApply = async () => {
        if (!sessionId) return;
        setApplying(true);
        setApplyError(null);
        try {
            const result = await applyAssistantSession(sessionId);
            setApplyResult(result);
        } catch (err: unknown) {
            const e = err as { message?: string; validationErrors?: string[] };
            if (e.validationErrors) {
                setApplyError("Validation errors:\n" + e.validationErrors.join("\n"));
            } else {
                setApplyError(e.message || "Failed to apply");
            }
        } finally {
            setApplying(false);
        }
    };

    if (loading) {
        return (
            <PageSection>
                <Spinner size="lg" />
            </PageSection>
        );
    }

    if (!session || !sessionId) {
        return (
            <PageSection>
                <Alert variant="danger" isInline title="Session not found" />
            </PageSection>
        );
    }

    const isConfigAssistant = session.templateId === "axiom-config-assistant";

    return (
        <PageSection padding={{ default: "noPadding" }} isFilled hasBodyWrapper={false}
            style={{
                display: "flex",
                flexDirection: "column",
                overflow: "hidden",
                minHeight: 0,
                flex: "1 1 0",
                gap: 0,
            }}>
            {/* Header — fixed at top */}
            <Flex style={{
                padding: "12px 16px",
                border: sessionMode === "plan" ? "1px solid #f0ab00" : undefined,
                borderBottom: sessionMode === "plan" ? "1px solid #f0ab00" : "1px solid #d2d2d2",
                borderRadius: sessionMode === "plan" ? "12px 12px 0 0" : undefined,
                alignItems: "center",
                flexShrink: 0,
                backgroundColor: sessionMode === "plan" ? "#fffaf0" : undefined,
                transition: "background-color 0.3s ease, border-color 0.3s ease",
            }}>
                {!isBreakout && (
                    <FlexItem>
                        <Button variant="plain" onClick={() => navigate("/assistant")}>
                            <ArrowLeftIcon />
                        </Button>
                    </FlexItem>
                )}
                <FlexItem grow={{ default: "grow" }}>
                    {isEditingName ? (
                        <TextInput
                            value={editName}
                            onChange={(_e, v) => setEditName(v)}
                            onBlur={handleNameSave}
                            onKeyDown={(e) => {
                                if (e.key === "Enter") handleNameSave();
                                if (e.key === "Escape") setIsEditingName(false);
                            }}
                            autoFocus
                            style={{ fontWeight: 600, fontSize: "16px", maxWidth: 300 }}
                        />
                    ) : (
                        <span
                            className="session-name-editable"
                            style={{ fontWeight: 600, fontSize: "16px", cursor: "pointer",
                                display: "inline-flex", alignItems: "center", gap: 6 }}
                            onClick={() => {
                                setEditName(session.name);
                                setIsEditingName(true);
                            }}
                        >
                            {session.name}
                            <PencilAltIcon className="session-name-pencil"
                                style={{ fontSize: "12px", color: "#6a6e73", opacity: 0,
                                    transition: "opacity 0.15s" }} />
                            <style>{`.session-name-editable:hover .session-name-pencil { opacity: 1 !important; }`}</style>
                        </span>
                    )}
                    {sessionMode === "plan" && (
                        <Label color="orange" isCompact style={{ marginLeft: 10 }}>
                            Plan Mode
                        </Label>
                    )}
                    {sessionModel && (
                        <Label color="grey" isCompact style={{ marginLeft: 10 }}>
                            {sessionModel}
                        </Label>
                    )}
                    {sessionCost > 0 && (
                        <Tooltip content={
                            `Tokens in: ${sessionInputTokens.toLocaleString()} / out: ${sessionOutputTokens.toLocaleString()}`
                        }>
                            <Label color="grey" isCompact style={{ marginLeft: 10 }}>
                                ${sessionCost.toFixed(4)}
                            </Label>
                        </Tooltip>
                    )}
                    {session.projectId && session.projectName && (
                        <Label
                            color="teal"
                            isCompact
                            style={{ marginLeft: 10, cursor: "pointer" }}
                            onClick={() => navigate(`/projects/${session.projectId}`)}
                        >
                            {session.projectName}
                        </Label>
                    )}
                </FlexItem>
                <FlexItem>
                    {isConfigAssistant && (
                        <Button
                            variant="primary"
                            onClick={handleApply}
                            isLoading={applying}
                            isDisabled={applying || itemCount === 0}
                            style={{ marginRight: 8 }}
                        >
                            Apply All
                        </Button>
                    )}
                    {autoApprovalCount > 0 && (
                        <Tooltip content={`${autoApprovalCount} auto-approval rule(s) active`}>
                            <Button variant="plain" onClick={openAutoApprovalModal}
                                style={{ marginRight: 4 }}>
                                <ShieldAltIcon color="#3e8635" />
                                <Badge isRead style={{ marginLeft: 4 }}>{autoApprovalCount}</Badge>
                            </Button>
                        </Tooltip>
                    )}
                    <Button
                        variant="secondary"
                        isDanger
                        onClick={() => setIsEndConfirmOpen(true)}
                        style={{ marginRight: isBreakout ? 0 : 8 }}
                    >
                        End Session
                    </Button>
                    {!isBreakout && (
                        <Tooltip content="Open in new window">
                            <Button
                                variant="plain"
                                aria-label="Open in new window"
                                onClick={handleBreakout}
                            >
                                <ExternalLinkAltIcon />
                            </Button>
                        </Tooltip>
                    )}
                </FlexItem>
            </Flex>

            {isConfigAssistant && applyError && (
                <Alert
                    variant="danger"
                    isInline
                    title="Apply failed"
                    style={{ margin: "8px 16px", flexShrink: 0 }}
                >
                    <pre style={{ whiteSpace: "pre-wrap", fontSize: "12px" }}>{applyError}</pre>
                </Alert>
            )}

            {/* Split panels — fills remaining height */}
            <div style={{
                display: "flex",
                flex: "1 1 0",
                minHeight: 0,
                overflow: "hidden",
            }}>
                <div style={{
                    flex: isConfigAssistant ? "7 1 0" : "1 1 0",
                    borderRight: isConfigAssistant ? "1px solid #d2d2d2" : "none",
                    display: "flex",
                    flexDirection: "column",
                    minWidth: 0,
                    minHeight: 0,
                }}>
                    <AssistantChatPanel
                        sessionId={sessionId}
                        onItemsChanged={isConfigAssistant ? handleItemsChanged : undefined}
                        onModeChange={setSessionMode}
                        onAutoApprovalCountChange={handleAutoApprovalCountChange}
                        onModelDetected={setSessionModel}
                        onCostUpdate={(cost, tokensIn, tokensOut) => {
                            setSessionCost(cost);
                            setSessionInputTokens(prev => prev + tokensIn);
                            setSessionOutputTokens(prev => prev + tokensOut);
                        }}
                    />
                </div>

                {isConfigAssistant && (
                    <div style={{
                        flex: "3 1 0",
                        overflowY: "auto",
                        minWidth: 0,
                        minHeight: 0,
                    }}>
                        <AssistantGeneratedItems
                            sessionId={sessionId}
                            refreshTrigger={itemsRefresh}
                            onItemCountChanged={handleItemCountChanged}
                        />
                    </div>
                )}
            </div>

            {/* End session confirmation */}
            <Modal
                isOpen={isEndConfirmOpen}
                onClose={() => setIsEndConfirmOpen(false)}
                variant="small"
                aria-label="End session confirmation"
            >
                <ModalHeader title="End Session?" />
                <ModalBody>
                    This will terminate the AI assistant and delete all generated items
                    that have not been applied. This action cannot be undone.
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={handleEndSession}>
                        End Session
                    </Button>
                    <Button variant="link" onClick={() => setIsEndConfirmOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            {/* Apply success */}
            {isConfigAssistant && (
                <Modal
                    isOpen={applyResult !== null}
                    onClose={() => {
                        setApplyResult(null);
                        navigate("/assistant");
                    }}
                    variant="small"
                    aria-label="Apply result"
                >
                    <ModalHeader title="Items Applied Successfully" />
                    <ModalBody>
                        {applyResult && (
                            <div>
                                <p>The following items were applied:</p>
                                <ul style={{ marginTop: 8 }}>
                                    {(applyResult.toolsCreated ?? 0) > 0 && <li>{applyResult.toolsCreated} tool(s) created</li>}
                                    {(applyResult.toolsUpdated ?? 0) > 0 && <li>{applyResult.toolsUpdated} tool(s) updated</li>}
                                    {(applyResult.actionTypesCreated ?? 0) > 0 && <li>{applyResult.actionTypesCreated} action type(s) created</li>}
                                    {(applyResult.actionTypesUpdated ?? 0) > 0 && <li>{applyResult.actionTypesUpdated} action type(s) updated</li>}
                                    {(applyResult.reportDefinitionsCreated ?? 0) > 0 && <li>{applyResult.reportDefinitionsCreated} report definition(s) created</li>}
                                    {(applyResult.reportDefinitionsUpdated ?? 0) > 0 && <li>{applyResult.reportDefinitionsUpdated} report definition(s) updated</li>}
                                    {(applyResult.toolsetsCreated ?? 0) > 0 && <li>{applyResult.toolsetsCreated} toolset(s) created</li>}
                                    {(applyResult.toolsetsUpdated ?? 0) > 0 && <li>{applyResult.toolsetsUpdated} toolset(s) updated</li>}
                                    {(applyResult.sessionTemplatesCreated ?? 0) > 0 && <li>{applyResult.sessionTemplatesCreated} session template(s) created</li>}
                                    {(applyResult.sessionTemplatesUpdated ?? 0) > 0 && <li>{applyResult.sessionTemplatesUpdated} session template(s) updated</li>}
                                </ul>
                            </div>
                        )}
                    </ModalBody>
                    <ModalFooter>
                        <Button variant="primary" onClick={() => {
                            setApplyResult(null);
                            navigate("/assistant");
                        }}>
                            Done
                        </Button>
                    </ModalFooter>
                </Modal>
            )}

            {/* Auto-approval management */}
            <Modal
                isOpen={isAutoApprovalModalOpen}
                onClose={() => setIsAutoApprovalModalOpen(false)}
                variant="medium"
                aria-label="Auto-approval rules"
            >
                <ModalHeader title="Auto-Approval Rules" />
                <ModalBody>
                    {autoApprovalRules.length === 0 ? (
                        <div style={{ color: "#6a6e73", fontStyle: "italic", padding: 16 }}>
                            No auto-approval rules active.
                        </div>
                    ) : (
                        <Table aria-label="Auto-approval rules" variant="compact">
                            <Thead>
                                <Tr>
                                    <Th>Tool</Th>
                                    <Th>Field</Th>
                                    <Th>Pattern</Th>
                                    <Th width={10}>Actions</Th>
                                </Tr>
                            </Thead>
                            <Tbody>
                                {autoApprovalRules.map((rule) => (
                                    <Tr key={rule.id}>
                                        <Td><Label isCompact>{rule.toolName}</Label></Td>
                                        <Td>{rule.fieldName || "—"}</Td>
                                        <Td>
                                            <code style={{ fontSize: "12px" }}>
                                                {rule.pattern || "(all)"}
                                            </code>
                                        </Td>
                                        <Td>
                                            <Button variant="plain" size="sm" isDanger
                                                onClick={() => handleDeleteAutoApproval(rule.id)}>
                                                <TrashIcon />
                                            </Button>
                                        </Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    )}
                </ModalBody>
                <ModalFooter>
                    <Button variant="link"
                        onClick={() => setIsAutoApprovalModalOpen(false)}>
                        Close
                    </Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
