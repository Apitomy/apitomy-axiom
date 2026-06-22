import { useState, useCallback, useRef } from "react";
import { TextArea, Button, Flex, FlexItem } from "@patternfly/react-core";
import PaperPlaneIcon from "@patternfly/react-icons/dist/esm/icons/paper-plane-icon";

interface AssistantMessageInputProps {
    onSend: (message: string) => void;
    disabled?: boolean;
}

export function AssistantMessageInput({ onSend, disabled }: AssistantMessageInputProps) {
    const [value, setValue] = useState("");
    const textAreaRef = useRef<HTMLTextAreaElement>(null);

    const handleSend = useCallback(() => {
        const trimmed = value.trim();
        if (!trimmed) return;
        onSend(trimmed);
        setValue("");
        // Defer to next tick so React flushes setValue("") before we reset height
        setTimeout(() => {
            // PF autoResize sets inline height on the textarea's parent <span>;
            // programmatic value changes don't trigger a recalc, so clear it manually.
            textAreaRef.current?.parentElement?.style.removeProperty("height");
        }, 0);
    }, [value, onSend]);

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    return (
        <Flex style={{ padding: "12px 16px", borderTop: "1px solid #d2d2d2", flexShrink: 0 }}>
            <FlexItem grow={{ default: "grow" }}>
                <TextArea
                    ref={textAreaRef}
                    value={value}
                    onChange={(_e, val) => setValue(val)}
                    onKeyDown={handleKeyDown}
                    placeholder="Type a message..."
                    aria-label="Message input"
                    autoResize
                    rows={1}
                    isDisabled={disabled}
                />
            </FlexItem>
            <FlexItem alignSelf={{ default: "alignSelfFlexEnd" }}>
                <Button
                    variant="primary"
                    onClick={handleSend}
                    isDisabled={disabled || !value.trim()}
                    aria-label="Send message"
                    icon={<PaperPlaneIcon />}
                />
            </FlexItem>
        </Flex>
    );
}
