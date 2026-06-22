import { useState } from "react";
import { HelperText, HelperTextItem, TextInput } from "@patternfly/react-core";
import { ColoredLabel } from "./ColoredLabel";

/**
 * Editable label list. Type a label and press Enter to add it.
 * Click the X on a label to remove it. Commas are not allowed
 * in label values (they are used as delimiters in filter queries).
 */
export function LabelInput({ labels, onChange }: {
    labels: string[];
    onChange: (labels: string[]) => void;
}) {
    const [input, setInput] = useState("");
    const [error, setError] = useState("");

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === "Enter") {
            e.preventDefault();
            const trimmed = input.trim();
            if (trimmed.includes(",")) {
                setError("Labels cannot contain commas");
                return;
            }
            setError("");
            if (trimmed && !labels.includes(trimmed)) {
                onChange([...labels, trimmed]);
            }
            setInput("");
        }
    };

    const handleRemove = (label: string) => {
        onChange(labels.filter((l) => l !== label));
    };

    return (
        <div>
            {labels.length > 0 && (
                <div style={{ display: "flex", flexWrap: "wrap", gap: "4px", marginBottom: "8px" }}>
                    {labels.map((label) => (
                        <ColoredLabel key={label}
                            onClose={() => handleRemove(label)}
                            closeBtnAriaLabel={`Remove ${label}`}>
                            {label}
                        </ColoredLabel>
                    ))}
                </div>
            )}
            <TextInput
                value={input}
                onChange={(_e, v) => { setInput(v); setError(""); }}
                onKeyDown={handleKeyDown}
                placeholder="Type a label and press Enter"
                aria-label="Add label"
                validated={error ? "error" : "default"}
            />
            {error && (
                <HelperText>
                    <HelperTextItem variant="error">{error}</HelperTextItem>
                </HelperText>
            )}
        </div>
    );
}
