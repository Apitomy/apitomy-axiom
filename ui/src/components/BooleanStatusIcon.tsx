import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import MinusCircleIcon from "@patternfly/react-icons/dist/esm/icons/minus-circle-icon";

/**
 * Renders a green check or grey minus icon for boolean table cells.
 */
export function BooleanStatusIcon({ value }: { value: boolean }) {
    return value
        ? <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" />
        : <MinusCircleIcon color="var(--pf-t--global--icon--color--disabled)" />;
}
