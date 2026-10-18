import { type ReactElement, useCallback, useState } from "react";
import { Alert, AlertActionCloseButton, AlertGroup } from "@patternfly/react-core";

interface ToastAlert {
    key: number;
    title: string;
    message?: string;
}

/** Return value of {@link useErrorToasts}. */
export interface ErrorToasts {
    /** Shows a danger toast; `error` may be an Error, a string or anything else. */
    showError: (title: string, error?: unknown) => void;
    /** The toast group element; render it once in the page. */
    toasts: ReactElement;
}

let nextKey = 1;

/**
 * Provides user-visible error toasts (PatternFly toast AlertGroup) for failed actions.
 *
 * @returns the function to show an error and the element to render
 */
export function useErrorToasts(): ErrorToasts {
    const [alerts, setAlerts] = useState<ToastAlert[]>([]);

    const remove = useCallback((key: number) => {
        setAlerts((prev) => prev.filter((a) => a.key !== key));
    }, []);

    const showError = useCallback((title: string, error?: unknown) => {
        console.error(title, error);
        const message = error instanceof Error ? error.message
            : typeof error === "string" ? error : undefined;
        setAlerts((prev) => [...prev, { key: nextKey++, title, message }]);
    }, []);

    const toasts = (
        <AlertGroup isToast isLiveRegion>
            {alerts.map((a) => (
                <Alert key={a.key} variant="danger" title={a.title} timeout={8000}
                       onTimeout={() => remove(a.key)}
                       actionClose={<AlertActionCloseButton onClose={() => remove(a.key)} />}>
                    {a.message}
                </Alert>
            ))}
        </AlertGroup>
    );

    return { showError, toasts };
}
