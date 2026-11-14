import { Button } from "@patternfly/react-core";
import { Table, Tbody, Td, Tr } from "@patternfly/react-table";
import ExternalLinkAltIcon from "@patternfly/react-icons/dist/esm/icons/external-link-alt-icon";

/** A single display-only input value for an inbox task. */
export interface InboxInputDetail {
    label: string;
    value: string;
}

/** Props for {@link InboxInputsTable}. */
export interface InboxInputsTableProps {
    /** The task's input values, displayed in order. */
    details: InboxInputDetail[];
}

/**
 * Returns true when the whole value (ignoring surrounding whitespace) is a single http or https URL.
 * Other schemes, such as {@code javascript:}, and text that merely contains a URL are rejected.
 *
 * @param value the input value to test
 */
export function isExternalUrl(value: string): boolean {
    const trimmed = value.trim();
    if (!/^https?:\/\/\S+$/i.test(trimmed)) {
        return false;
    }
    try {
        const url = new URL(trimmed);
        return url.protocol === "http:" || url.protocol === "https:";
    } catch {
        return false;
    }
}

function renderValue(value: string | undefined) {
    if (!value?.trim()) {
        return <span className="axiom-text-subtle">&mdash;</span>;
    }
    if (isExternalUrl(value)) {
        return (
            <Button
                variant="link"
                isInline
                component="a"
                href={value.trim()}
                target="_blank"
                rel="noopener noreferrer"
                icon={<ExternalLinkAltIcon />}
                iconPosition="end"
            >
                {value.trim()}
            </Button>
        );
    }
    return <span style={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}>{value}</span>;
}

/**
 * Displays an inbox task's inputs as a compact, header-less, zebra-striped name/value table. Values that
 * are http(s) URLs are rendered as external links that open in a new tab.
 */
export function InboxInputsTable({ details }: InboxInputsTableProps) {
    return (
        <Table aria-label="Task inputs" variant="compact" isStriped className="axiom-inbox-inputs">
            <Tbody>
                {details.map((d, i) => (
                    <Tr key={`${d.label}-${i}`}>
                        <Td modifier="nowrap" width={25} style={{ fontWeight: 600 }}>{d.label}</Td>
                        <Td>{renderValue(d.value)}</Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}
