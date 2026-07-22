import { type ReactNode, useEffect, useRef, useState } from "react";
import {
    Button,
    Content,
    Grid,
    GridItem,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    Spinner,
    Stack,
    StackItem,
    TextArea,
} from "@patternfly/react-core";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import PaperPlaneIcon from "@patternfly/react-icons/dist/esm/icons/paper-plane-icon";

interface ChatMessage {
    role: "user" | "assistant";
    content: string;
}

interface AiEditModalProps {
    isOpen: boolean;
    onClose: () => void;
    onApply: () => void;
    title: string;
    placeholder: string;
    emptyHint: string;
    onSendMessage: (message: string) => Promise<{ explanation: string }>;
    children: ReactNode;
}

/**
 * Shared modal for AI-assisted editing. Renders a 9:3 grid with the
 * caller-provided content panel on the left and a chat interface on
 * the right. The modal manages all chat state internally.
 */
export function AiEditModal({
    isOpen, onClose, onApply, title, placeholder, emptyHint,
    onSendMessage, children,
}: AiEditModalProps) {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState("");
    const [loading, setLoading] = useState(false);
    const messagesEndRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }, [messages]);

    const handleSend = () => {
        const text = input.trim();
        if (!text || loading) return;

        setMessages((prev) => [...prev, { role: "user", content: text }]);
        setInput("");
        setLoading(true);

        onSendMessage(text)
            .then((response) => {
                setMessages((prev) => [
                    ...prev,
                    { role: "assistant", content: response.explanation || "Done." },
                ]);
            })
            .catch((err) => {
                setMessages((prev) => [
                    ...prev,
                    { role: "assistant", content: "Error: " + err.message },
                ]);
            })
            .finally(() => setLoading(false));
    };

    const handleApply = () => {
        onApply();
        onClose();
    };

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large"
            style={{ maxWidth: "95vw", width: "95vw" }}>
            <ModalHeader title={title} />
            <ModalBody style={{ padding: 0, height: "100vh", display: "flex" }}>
                <Grid style={{ width: "100%" }}>
                    <GridItem className="content" span={9}>
                        <Stack>
                            {children}
                        </Stack>
                    </GridItem>
                    <GridItem className="chat" span={3} style={{
                        borderLeft: "1px solid var(--pf-t--global--border--color--default)",
                    }}>
                        <Stack>
                            <StackItem isFilled style={{
                                overflowY: "auto",
                                padding: "16px",
                                display: "flex",
                                flexDirection: "column",
                                gap: "12px",
                            }}>
                                {messages.length === 0 && (
                                    <div className="axiom-text-subtle" style={{
                                        fontSize: "13px", textAlign: "center",
                                        marginTop: "32px",
                                    }}>
                                        {emptyHint}
                                    </div>
                                )}
                                {messages.map((msg, i) => (
                                    <div key={i} style={{
                                        alignSelf: msg.role === "user" ? "flex-end" : "flex-start",
                                        maxWidth: "90%",
                                        padding: "8px 12px",
                                        borderRadius: "8px",
                                        backgroundColor: msg.role === "user"
                                            ? "var(--pf-t--global--color--brand--default)"
                                            : "var(--pf-t--global--background--color--secondary--default)",
                                        color: msg.role === "user" ? "var(--pf-t--global--background--color--primary--default, #fff)" : "inherit",
                                        fontSize: "13px",
                                    }}>
                                        {msg.role === "assistant" ? (
                                            <Content>
                                                <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
                                            </Content>
                                        ) : msg.content}
                                    </div>
                                ))}
                                {loading && (
                                    <div className="axiom-text-subtle" style={{
                                        alignSelf: "flex-start",
                                        padding: "8px 12px",
                                        borderRadius: "8px",
                                        backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                        fontSize: "13px",
                                    }}>
                                        <Spinner size="sm" style={{ marginRight: "5px" }} />
                                        Thinking...
                                    </div>
                                )}
                                <div ref={messagesEndRef} />
                            </StackItem>
                            <StackItem style={{
                                padding: "6px 8px",
                                borderTop: "1px solid var(--pf-t--global--border--color--default)",
                                display: "flex",
                                gap: "8px",
                            }}>
                                <TextArea
                                    value={input}
                                    onChange={(_e, v) => setInput(v)}
                                    onKeyDown={(e) => {
                                        if (e.key === "Enter" && !e.shiftKey) {
                                            e.preventDefault();
                                            handleSend();
                                        }
                                    }}
                                    placeholder={placeholder}
                                    rows={2}
                                    isDisabled={loading}
                                    style={{ flex: 1, resize: "none" }}
                                />
                                <Button
                                    variant="primary"
                                    onClick={handleSend}
                                    isDisabled={!input.trim() || loading}
                                    style={{ alignSelf: "flex-end" }}
                                >
                                    <PaperPlaneIcon />
                                </Button>
                            </StackItem>
                        </Stack>
                    </GridItem>
                </Grid>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={handleApply}>Apply Changes</Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
