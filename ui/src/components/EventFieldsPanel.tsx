import { useEffect, useMemo, useState } from "react";
import {
    Alert,
    Button,
    ExpandableSection,
    FormGroup,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    type EventFieldRow,
    defaultEventType,
    formatExample,
    schemaToPaths,
    typesFromFilterExpression,
} from "../config/eventSchemaPaths";
import { useEventSchema, useEventSchemas } from "../hooks/useEventSchemas";

/** Props of {@link EventFieldsPanel}. */
export interface EventFieldsPanelProps {
    /** Current filter expression; `event.type == '...'` comparisons restrict the type picker. */
    filterExpression: string;
    /** Called with a field path when the user clicks it. */
    onSelectPath: (path: string) => void;
    /** Called whenever the rows for the selected type change (used to feed editor completion). */
    onRowsChange?: (rows: EventFieldRow[]) => void;
}

/**
 * Schema-driven reference of the EL fields available for an event type. Shows the types named in the filter
 * expression, or every type when the expression names none.
 *
 * @param props the panel props
 * @returns the panel
 */
export function EventFieldsPanel({ filterExpression, onSelectPath, onRowsChange }: EventFieldsPanelProps) {
    const [expanded, setExpanded] = useState<boolean>(true);
    const [picked, setPicked] = useState<string | undefined>(undefined);
    const list = useEventSchemas();

    const allTypes: string[] = useMemo(() => (list.data ?? []).map((s) => s.type), [list.data]);
    const filterTypes: string[] = useMemo(
        () => typesFromFilterExpression(filterExpression, allTypes), [filterExpression, allTypes]);
    const choices: string[] = filterTypes.length > 0 ? filterTypes : allTypes;
    const selected: string | undefined = picked && choices.includes(picked) ? picked : defaultEventType(choices);

    const schema = useEventSchema(selected);
    const rows: EventFieldRow[] = useMemo(() => (schema.data ? schemaToPaths(schema.data) : []), [schema.data]);

    useEffect(() => {
        onRowsChange?.(rows);
    }, [rows, onRowsChange]);

    return (
        <ExpandableSection toggleText="Event fields" isExpanded={expanded}
            onToggle={(_e, value) => setExpanded(value)} style={{ marginTop: "16px" }}>
            {list.error ? (
                <Alert variant="warning" isInline title="Event field reference is unavailable">
                    {list.error}
                </Alert>
            ) : (
                <>
                    <FormGroup label="Event type" fieldId="event-fields-type" style={{ maxWidth: "400px" }}>
                        <FormSelect id="event-fields-type" aria-label="Event type" value={selected ?? ""}
                            onChange={(_e, value) => setPicked(value)} isDisabled={choices.length === 0}>
                            {choices.map((t) => <FormSelectOption key={t} value={t} label={t} />)}
                        </FormSelect>
                        <HelperText>
                            <HelperTextItem>
                                {filterTypes.length > 0
                                    ? "Showing the event types used in your filter expression."
                                    : "Your filter expression does not restrict event.type; pick a type to see its fields."}
                            </HelperTextItem>
                        </HelperText>
                    </FormGroup>
                    {schema.data?.description && (
                        <p style={{ marginTop: "8px" }}>{schema.data.description}</p>
                    )}
                    {schema.error && (
                        <Alert variant="warning" isInline title="Could not load the schema" style={{ marginTop: "8px" }}>
                            {schema.error}
                        </Alert>
                    )}
                    <div style={{ maxHeight: "320px", overflowY: "auto", marginTop: "8px" }}>
                        <Table aria-label="Event fields" variant="compact" isStickyHeader>
                            <Thead>
                                <Tr>
                                    <Th>Path</Th>
                                    <Th>Type</Th>
                                    <Th>Description</Th>
                                    <Th>Example</Th>
                                </Tr>
                            </Thead>
                            <Tbody>
                                {rows.map((row) => (
                                    <Tr key={row.path}>
                                        <Td>
                                            <Button variant="link" isInline aria-label={`Insert ${row.path}`}
                                                onClick={() => onSelectPath(row.path)}>
                                                <code>{row.path}</code>
                                            </Button>
                                        </Td>
                                        <Td>{row.type}</Td>
                                        <Td>{row.description}</Td>
                                        <Td><code>{formatExample(row.examples)}</code></Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    </div>
                </>
            )}
        </ExpandableSection>
    );
}
