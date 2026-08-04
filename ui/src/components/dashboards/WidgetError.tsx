import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";

/**
 * Inline error indicator for widgets that fail to load data.
 */
export function WidgetError() {
    return (
        <div style={{ padding: "16px", textAlign: "center",
                      color: "var(--pf-t--global--color--status--danger--default)" }}>
            <ExclamationTriangleIcon style={{ marginRight: "6px" }} />
            Error loading data.
        </div>
    );
}
