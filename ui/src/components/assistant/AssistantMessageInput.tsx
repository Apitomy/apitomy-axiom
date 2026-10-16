import { useState, useCallback, useEffect, useRef, useMemo } from "react";
import { TextArea, Button, Flex, FlexItem } from "@patternfly/react-core";
import PaperPlaneIcon from "@patternfly/react-icons/dist/esm/icons/paper-plane-icon";
import StopIcon from "@patternfly/react-icons/dist/esm/icons/stop-icon";
import "./AssistantMessageInput.css";

interface AssistantMessageInputProps {
    onSend: (message: string) => void;
    disabled?: boolean;
    /** Overrides the default placeholder text when set. */
    placeholder?: string;
    /** When the nonce changes, replaces the input text and focuses the input. */
    prefill?: { text: string; nonce: number };
    /** When true, a Stop button is shown next to the Send button. */
    isProcessing?: boolean;
    /** True while a stop request is in flight; disables the Stop button. */
    isStopping?: boolean;
    /** Called when the user clicks Stop to interrupt the current reply. */
    onStop?: () => void;
    slashCommands?: string[];
}

export function AssistantMessageInput({
    onSend, disabled, placeholder, prefill, slashCommands = [], isProcessing = false, isStopping = false, onStop,
}: AssistantMessageInputProps) {
    const [value, setValue] = useState("");
    const prefillNonce: number | undefined = prefill?.nonce;
    const prefillText: string | undefined = prefill?.text;
    const [selectedIndex, setSelectedIndex] = useState(0);
    const textAreaRef = useRef<HTMLTextAreaElement>(null);

    /**
     * Resizes the auto-resizing text area to its content; needed when the value is set programmatically.
     * Mirrors PatternFly TextArea's own auto-resize: content height plus the field's borders and the
     * wrapper's padding.
     */
    const resizeToContent = useCallback(() => {
        const textarea = textAreaRef.current;
        const parent = textarea?.parentElement;
        if (!textarea || !parent) {
            return;
        }
        parent.style.setProperty("height", "inherit");
        const field = window.getComputedStyle(textarea);
        const wrapper = window.getComputedStyle(parent);
        const height: number = (parseInt(field.borderTopWidth) || 0) + textarea.scrollHeight
            + (parseInt(field.borderBottomWidth) || 0)
            + (parseInt(wrapper.paddingTop) || 0) + (parseInt(wrapper.paddingBottom) || 0);
        parent.style.setProperty("height", `${height}px`);
    }, []);

    useEffect(() => {
        if (prefillNonce === undefined) {
            return;
        }
        setValue(prefillText ?? "");
        textAreaRef.current?.focus();
        // Wait for the new value to render before measuring it.
        setTimeout(() => {
            resizeToContent();
            const textarea = textAreaRef.current;
            if (textarea) {
                textarea.selectionStart = textarea.selectionEnd = textarea.value.length;
            }
        }, 0);
        // Only a new nonce applies the prefill; text alone must not overwrite user edits.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [prefillNonce]);
    const dropdownRef = useRef<HTMLDivElement>(null);

    const filteredCommands = useMemo(() => {
        if (!value.startsWith("/") || value.includes(" ") || value.includes("\n")) {
            return [];
        }
        const query = value.substring(1).toLowerCase();
        return slashCommands
            .filter((cmd) => cmd.toLowerCase().includes(query))
            .slice(0, 15);
    }, [value, slashCommands]);

    const showDropdown = filteredCommands.length > 0;

    useEffect(() => {
        setSelectedIndex(0);
    }, [filteredCommands]);

    useEffect(() => {
        if (showDropdown && dropdownRef.current) {
            const selected = dropdownRef.current.children[selectedIndex] as HTMLElement;
            selected?.scrollIntoView({ block: "nearest" });
        }
    }, [selectedIndex, showDropdown]);

    const handleSend = useCallback(() => {
        const trimmed = value.trim();
        if (!trimmed) return;
        onSend(trimmed);
        setValue("");
        setTimeout(() => {
            textAreaRef.current?.parentElement?.style.removeProperty("height");
        }, 0);
    }, [value, onSend]);

    useEffect(() => {
        if (!disabled) {
            textAreaRef.current?.focus();
        }
    }, [disabled]);

    const selectCommand = useCallback((cmd: string) => {
        setValue("/" + cmd + " ");
        textAreaRef.current?.focus();
    }, []);

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (showDropdown) {
            if (e.key === "ArrowDown") {
                e.preventDefault();
                setSelectedIndex((prev) =>
                    prev < filteredCommands.length - 1 ? prev + 1 : 0
                );
                return;
            }
            if (e.key === "ArrowUp") {
                e.preventDefault();
                setSelectedIndex((prev) =>
                    prev > 0 ? prev - 1 : filteredCommands.length - 1
                );
                return;
            }
            if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey)) {
                e.preventDefault();
                selectCommand(filteredCommands[selectedIndex]);
                return;
            }
            if (e.key === "Escape") {
                e.preventDefault();
                setValue("");
                return;
            }
        }

        if (e.key === "Enter" && !e.shiftKey && !e.ctrlKey && !e.altKey) {
            e.preventDefault();
            handleSend();
        }
        if (e.key === "Enter" && (e.ctrlKey || e.altKey)) {
            e.preventDefault();
            const textarea = textAreaRef.current;
            if (textarea) {
                const start = textarea.selectionStart;
                const end = textarea.selectionEnd;
                const newValue = value.substring(0, start) + "\n" + value.substring(end);
                setValue(newValue);
                setTimeout(() => {
                    textarea.selectionStart = textarea.selectionEnd = start + 1;
                    resizeToContent();
                }, 0);
            }
        }
    };

    return (
        <div className="axiom-message-input">
            {showDropdown && (
                <div ref={dropdownRef} className="axiom-message-input__dropdown">
                    {filteredCommands.map((cmd, i) => (
                        <div
                            key={cmd}
                            className="axiom-message-input__dropdown-item"
                            data-selected={i === selectedIndex || undefined}
                            onClick={() => selectCommand(cmd)}
                            onMouseEnter={() => setSelectedIndex(i)}
                        >
                            /{cmd}
                        </div>
                    ))}
                </div>
            )}
            <Flex className="axiom-message-input__bar">
                <FlexItem grow={{ default: "grow" }}>
                    <TextArea
                        ref={textAreaRef}
                        value={value}
                        onChange={(_e, val) => { if (val !== value) setValue(val); }}
                        onKeyDown={handleKeyDown}
                        placeholder={placeholder ?? "Type a message... (/ for commands)"}
                        aria-label="Message input"
                        autoResize
                        rows={1}
                        isDisabled={disabled}
                    />
                </FlexItem>
                {isProcessing && onStop && (
                    <FlexItem alignSelf={{ default: "alignSelfFlexEnd" }}>
                        <Button
                            variant="danger"
                            onClick={onStop}
                            isDisabled={isStopping}
                            isLoading={isStopping}
                            aria-label="Stop the current reply"
                            title="Stop the current reply"
                            icon={isStopping ? undefined : <StopIcon />}
                        >
                            Stop
                        </Button>
                    </FlexItem>
                )}
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
        </div>
    );
}
