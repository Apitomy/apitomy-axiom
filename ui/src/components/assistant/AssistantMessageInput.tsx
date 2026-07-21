import { useState, useCallback, useEffect, useRef, useMemo } from "react";
import { TextArea, Button, Flex, FlexItem } from "@patternfly/react-core";
import PaperPlaneIcon from "@patternfly/react-icons/dist/esm/icons/paper-plane-icon";
import "./AssistantMessageInput.css";

interface AssistantMessageInputProps {
    onSend: (message: string) => void;
    disabled?: boolean;
    slashCommands?: string[];
}

export function AssistantMessageInput({ onSend, disabled, slashCommands = [] }: AssistantMessageInputProps) {
    const [value, setValue] = useState("");
    const [selectedIndex, setSelectedIndex] = useState(0);
    const textAreaRef = useRef<HTMLTextAreaElement>(null);
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
                    const parent = textarea.parentElement;
                    if (parent) {
                        parent.style.removeProperty("height");
                        parent.style.height = textarea.scrollHeight + "px";
                    }
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
                        onChange={(_e, val) => setValue(val)}
                        onKeyDown={handleKeyDown}
                        placeholder="Type a message... (/ for commands)"
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
        </div>
    );
}
