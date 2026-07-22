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
import "./AssistantSessionPage.css";

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
            className="axiom-session-page">
            {/* Header — fixed at top */}
            <Flex className="axiom-session-page__header"
                data-mode={sessionMode === "plan" ? "plan" : undefined}>
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
                            className="axiom-session-page__name-input"
                        />
                    ) : (
                        <span
                            className="axiom-session-page__name-editable"
                            onClick={() => {
                                setEditName(session.name);
                                setIsEditingName(true);
                            }}
                        >
                            {session.name}
                            <PencilAltIcon className="axiom-session-page__name-pencil" />
                        </span>
                    )}
                    {sessionMode === "plan" && (
                        <Label color="orange" isCompact className="axiom-session-page__header-label">
                            Plan Mode
                        </Label>
                    )}
                    {sessionModel && (
                        <Label color="grey" isCompact className="axiom-session-page__header-label">
                            {sessionModel}
                        </Label>
                    )}
                    {sessionCost > 0 && (
                        <Tooltip content={
                            `Tokens in: ${sessionInputTokens.toLocaleString()} / out: ${sessionOutputTokens.toLocaleString()}`
                        }>
                            <Label color="grey" isCompact className="axiom-session-page__header-label">
                                ${sessionCost.toFixed(4)}
                            </Label>
                        </Tooltip>
                    )}
                    {session.projectId && session.projectName && (
                        <Label
                            color="teal"
                            isCompact
                            className="axiom-session-page__header-label--clickable"
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
                            className="axiom-session-page__apply-btn"
                        >
                            Apply All
                        </Button>
                    )}
                    {autoApprovalCount > 0 && (
                        <Tooltip content={`${autoApprovalCount} auto-approval rule(s) active`}>
                            <Button variant="plain" onClick={openAutoApprovalModal}
                                className="axiom-session-page__shield-btn">
                                <ShieldAltIcon className="axiom-session-page__shield-icon" />
                                <Badge isRead className="axiom-session-page__shield-badge">{autoApprovalCount}</Badge>
                            </Button>
                        </Tooltip>
                    )}
                    <Button
                        variant="secondary"
                        isDanger
                        onClick={() => setIsEndConfirmOpen(true)}
                        className={isBreakout ? "axiom-session-page__end-btn--breakout" : "axiom-session-page__end-btn"}
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
                    className="axiom-session-page__apply-error"
                >
                    <pre className="axiom-session-page__apply-error-pre">{applyError}</pre>
                </Alert>
            )}

            {/* Split panels — fills remaining height */}
            <div className="axiom-session-page__split-panels">
                <div className={`axiom-session-page__chat-panel${isConfigAssistant ? " axiom-session-page__chat-panel--with-sidebar" : ""}`}>
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
                    <div className="axiom-session-page__items-panel">
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
                    {isConfigAssistant
                        ? "This will terminate the AI assistant and delete all generated items that have not been applied. This action cannot be undone."
                        : "This will terminate the AI assistant and delete the session. This action cannot be undone."}
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
                                <ul className="axiom-session-page__apply-result-list">
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
                        <div className="axiom-session-page__auto-approval-empty">
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
                                            <code className="axiom-session-page__auto-approval-code">
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
