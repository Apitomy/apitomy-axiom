import { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate, useSearchParams } from "react-router-dom";
import {
    PageSection,
    Button,
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
import ArrowLeftIcon from "@patternfly/react-icons/dist/esm/icons/arrow-left-icon";
import ExternalLinkAltIcon from "@patternfly/react-icons/dist/esm/icons/external-link-alt-icon";
import { AssistantChatPanel, type SessionMode } from "../components/assistant/AssistantChatPanel";
import { AssistantGeneratedItems } from "../components/assistant/AssistantGeneratedItems";
import {
    fetchAssistantSession,
    deleteAssistantSession,
    applyAssistantSession,
    type AssistantSessionInfo,
    type ImportResult,
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
    const [applyResult, setApplyResult] = useState<ImportResult | null>(null);
    const [applyError, setApplyError] = useState<string | null>(null);
    const [sessionMode, setSessionMode] = useState<SessionMode>("normal");
    const [itemCount, setItemCount] = useState(0);

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
    }, [sessionId, navigate, isBreakout]);

    const handleItemsChanged = useCallback(() => {
        setItemsRefresh((n) => n + 1);
    }, []);

    const handleItemCountChanged = useCallback((count: number) => {
        setItemCount(count);
    }, []);

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
                    <span style={{ fontWeight: 600, fontSize: "16px" }}>{session.name}</span>
                    {sessionMode === "plan" && (
                        <Label color="orange" isCompact style={{ marginLeft: 10 }}>
                            Plan Mode
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
                                <p>The following items were imported:</p>
                                <ul style={{ marginTop: 8 }}>
                                    {(applyResult.tools ?? 0) > 0 && <li>{applyResult.tools} tool(s)</li>}
                                    {(applyResult.actionTypes ?? 0) > 0 && <li>{applyResult.actionTypes} action type(s)</li>}
                                    {(applyResult.reportDefinitions ?? 0) > 0 && <li>{applyResult.reportDefinitions} report definition(s)</li>}
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
        </PageSection>
    );
}
