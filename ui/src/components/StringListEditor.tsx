import { useState } from "react";
import { Button, Flex, FlexItem, TextInput } from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import AngleUpIcon from "@patternfly/react-icons/dist/esm/icons/angle-up-icon";
import AngleDownIcon from "@patternfly/react-icons/dist/esm/icons/angle-down-icon";

/** Props of {@link StringListEditor}. */
export interface StringListEditorProps {
    /** Accessible label of the list (e.g. "Arguments"). */
    label: string;
    /** The items, in order. */
    items: string[];
    /** Called with the new items. */
    onChange: (items: string[]) => void;
    /** Placeholder for the new-item input. */
    placeholder?: string;
}

/** Ordered list of strings with inline editing, add, remove and reorder. */
export function StringListEditor({ label, items, onChange, placeholder }: StringListEditorProps) {
    const [newItem, setNewItem] = useState("");

    const add = () => {
        if (!newItem) return;
        onChange([...items, newItem]);
        setNewItem("");
    };
    const move = (index: number, delta: number) => {
        const next = [...items];
        [next[index], next[index + delta]] = [next[index + delta], next[index]];
        onChange(next);
    };

    return (
        <div>
            {items.map((item, i) => (
                <Flex key={i} alignItems={{ default: "alignItemsCenter" }} style={{ marginBottom: "4px", gap: "4px" }}>
                    <FlexItem grow={{ default: "grow" }}>
                        <TextInput value={item} aria-label={`${label} item ${i + 1}`} style={{ fontFamily: "monospace" }}
                            onChange={(_e, v) => onChange(items.map((x, j) => (j === i ? v : x)))} />
                    </FlexItem>
                    <FlexItem>
                        <Button variant="plain" size="sm" aria-label={`Move ${item} up`} isDisabled={i === 0}
                            onClick={() => move(i, -1)}><AngleUpIcon /></Button>
                        <Button variant="plain" size="sm" aria-label={`Move ${item} down`}
                            isDisabled={i === items.length - 1} onClick={() => move(i, 1)}><AngleDownIcon /></Button>
                        <Button variant="plain" size="sm" aria-label={`Remove ${item}`}
                            onClick={() => onChange(items.filter((_, j) => j !== i))}><TimesIcon /></Button>
                    </FlexItem>
                </Flex>
            ))}
            <Flex alignItems={{ default: "alignItemsCenter" }} style={{ gap: "4px" }}>
                <FlexItem grow={{ default: "grow" }}>
                    <TextInput value={newItem} aria-label={`New ${label} item`} placeholder={placeholder}
                        style={{ fontFamily: "monospace" }} onChange={(_e, v) => setNewItem(v)}
                        onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />} onClick={add} isDisabled={!newItem}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>
        </div>
    );
}
