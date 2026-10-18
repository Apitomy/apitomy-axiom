import { useState } from "react";
import { MenuToggle, Select, SelectList, SelectOption } from "@patternfly/react-core";

interface MultiSelectFieldProps {
    id: string;
    label: string;
    options: { label: string; value: string }[];
    value: string[];
    onChange: (value: string[]) => void;
}

/**
 * Checkbox multi-select. An empty selection means "All".
 */
export function MultiSelectField({ id, label, options, value, onChange }: MultiSelectFieldProps) {
    const [open, setOpen] = useState(false);
    const selectedLabels = options.filter((o) => value.includes(o.value)).map((o) => o.label);
    const text = selectedLabels.length === 0 ? "All" : selectedLabels.join(", ");

    return (
        <Select
            id={id}
            role="menu"
            isOpen={open}
            selected={value}
            onOpenChange={setOpen}
            onSelect={(_e, selection) => {
                const v = String(selection);
                onChange(value.includes(v) ? value.filter((x) => x !== v) : [...value, v]);
            }}
            toggle={(ref) => (
                <MenuToggle ref={ref} aria-label={label} isExpanded={open} isFullWidth
                            onClick={() => setOpen((o) => !o)}>
                    {text}
                </MenuToggle>
            )}
        >
            <SelectList aria-label={`${label} options`}>
                {options.map((o) => (
                    <SelectOption key={o.value} value={o.value} hasCheckbox isSelected={value.includes(o.value)}>
                        {o.label}
                    </SelectOption>
                ))}
            </SelectList>
        </Select>
    );
}
