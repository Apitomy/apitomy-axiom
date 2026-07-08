import { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
    PageSection,
    Button,
    Flex,
    FlexItem,
    Spinner,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    Alert,
} from "@patternfly/react-core";
import ArrowLeftIcon from "@patternfly/react-icons/dist/esm/icons/arrow-left-icon";
import { AssistantChatPanel } from "../components/assistant/AssistantChatPanel";
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
    const [session, setSession] = useState<AssistantSessionInfo | null>(null);
    const [loading, setLoading] = useState(true);
    const [itemsRefresh, setItemsRefresh] = useState(0);
    const [isEndConfirmOpen, setIsEndConfirmOpen] = useState(false);
    const [applying, setApplying] = useState(false);
    const [applyResult, setApplyResult] = useState<ImportResult | null>(null);
    const [applyError, setApplyError] = useState<string | null>(null);

    useEffect(() => {
        if (!sessionId) return;
        setLoading(true);
        fetchAssistantSession(sessionId)
            .then(setSession)
            .catch((err) => {
                console.error("Failed to load session:", err);
                navigate("/assistant");
            })
            .finally(() => setLoading(false));
    }, [sessionId, navigate]);

    const handleItemsChanged = useCallback(() => {
        setItemsRefresh((n) => n + 1);
    }, []);

    const handleEndSession = async () => {
        if (!sessionId) return;
        try {
            await deleteAssistantSession(sessionId);
            navigate("/assistant");
        } catch (err) {
            console.error("Failed to end session:", err);
        }
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
            }}>
            {/* Header — fixed at top */}
            <Flex style={{
                padding: "12px 16px",
                borderBottom: "1px solid #d2d2d2",
                alignItems: "center",
                flexShrink: 0,
            }}>
                <FlexItem>
                    <Button variant="plain" onClick={() => navigate("/assistant")}>
                        <ArrowLeftIcon />
                    </Button>
                </FlexItem>
                <FlexItem grow={{ default: "grow" }}>
                    <span style={{ fontWeight: 600, fontSize: "16px" }}>{session.name}</span>
                </FlexItem>
                <FlexItem>
                    {isConfigAssistant && (
                        <Button
                            variant="primary"
                            onClick={handleApply}
                            isLoading={applying}
                            isDisabled={applying}
                            style={{ marginRight: 8 }}
                        >
                            Apply All
                        </Button>
                    )}
                    <Button
                        variant="secondary"
                        isDanger
                        onClick={() => setIsEndConfirmOpen(true)}
                    >
                        End Session
                    </Button>
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
                        templateId={session.templateId}
                        onItemsChanged={isConfigAssistant ? handleItemsChanged : undefined}
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
