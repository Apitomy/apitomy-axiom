import { useEffect, useState } from "react";
import {
    ExpandableSection,
    Label,
    Spinner,
    Switch,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { type ConfigurationSnapshot } from "../config/api";

interface ConfigurationUsedProps {
    /** Loads the snapshot; resolves to null when none was recorded. */
    load: () => Promise<ConfigurationSnapshot | null>;
    /** What the configuration belongs to, used in messages ("run" or "report"). */
    noun: string;
}

const preStyle: React.CSSProperties = {
    whiteSpace: "pre-wrap",
    wordBreak: "break-word",
    margin: 0,
    maxHeight: "200px",
    overflow: "auto",
    fontSize: "var(--pf-v5-global--FontSize--sm)",
};

/**
 * Shows the configuration a scheduled job run or report used (#426), with a
 * "changed since" indicator and a field-by-field comparison with the current definition.
 */
export function ConfigurationUsed({ load, noun }: ConfigurationUsedProps) {
    const [snapshot, setSnapshot] = useState<ConfigurationSnapshot | null | undefined>(undefined);
    const [error, setError] = useState<string | null>(null);
    const [expanded, setExpanded] = useState(false);
    const [onlyChanged, setOnlyChanged] = useState(false);

    useEffect(() => {
        let cancelled = false;
        load()
            .then((s) => { if (!cancelled) setSnapshot(s); })
            .catch((e) => { if (!cancelled) setError(String(e)); });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const toggle = (
        <span>
            Configuration used{" "}
            {snapshot && (snapshot.changed
                ? <Label color="orange" isCompact>Changed since this {noun}</Label>
                : <Label color="green" isCompact>Matches current definition</Label>)}
        </span>
    );

    if (error) {
        return <p className="axiom-text-subtle">Configuration used: {error}</p>;
    }
    if (snapshot === undefined) {
        return <Spinner size="sm" aria-label="Loading configuration" />;
    }
    if (snapshot === null) {
        return (
            <p className="axiom-text-subtle">
                No configuration was recorded for this {noun} (created before configuration tracking).
            </p>
        );
    }

    const fields = onlyChanged ? snapshot.fields.filter((f) => f.changed) : snapshot.fields;
    return (
        <ExpandableSection toggleContent={toggle} isExpanded={expanded}
            onToggle={(_e, value) => setExpanded(value)}>
            {snapshot.changed && (
                <Switch id={`config-only-changed-${snapshot.versionId}`} label="Only changed fields"
                    isChecked={onlyChanged} onChange={(_e, value) => setOnlyChanged(value)}
                    style={{ marginBottom: "8px" }} />
            )}
            <Table variant="compact" aria-label="Configuration used">
                <Thead>
                    <Tr>
                        <Th width={15}>Field</Th>
                        <Th width={40}>Used by this {noun}</Th>
                        {snapshot.changed && <Th width={40}>Current definition</Th>}
                    </Tr>
                </Thead>
                <Tbody>
                    {fields.map((f) => (
                        <Tr key={f.name}>
                            <Td dataLabel="Field">
                                {f.name}{" "}
                                {f.changed && <Label color="orange" isCompact>changed</Label>}
                            </Td>
                            <Td dataLabel="Used"><pre style={preStyle}>{f.value ?? "—"}</pre></Td>
                            {snapshot.changed && (
                                <Td dataLabel="Current">
                                    <pre style={preStyle}>{f.currentValue ?? "—"}</pre>
                                </Td>
                            )}
                        </Tr>
                    ))}
                </Tbody>
            </Table>
            <Title headingLevel="h6" size="md" className="axiom-text-subtle" style={{ marginTop: "4px" }}>
                Version {snapshot.versionId} · {snapshot.configHash.substring(0, 12)}
                {snapshot.capturedOn && ` · first recorded ${new Date(snapshot.capturedOn).toLocaleString()}`}
            </Title>
        </ExpandableSection>
    );
}
