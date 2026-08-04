import { Component, type ErrorInfo, type ReactNode } from "react";
import { WidgetError } from "./WidgetError";

interface Props {
    children: ReactNode;
}

interface State {
    hasError: boolean;
}

/**
 * Catches rendering errors in widget components so one broken widget
 * does not crash the entire dashboard.
 */
export class WidgetErrorBoundary extends Component<Props, State> {
    state: State = { hasError: false };

    static getDerivedStateFromError(): State {
        return { hasError: true };
    }

    componentDidCatch(error: Error, info: ErrorInfo): void {
        console.error("Widget rendering error:", error, info.componentStack);
    }

    render(): ReactNode {
        if (this.state.hasError) {
            return <WidgetError />;
        }
        return this.props.children;
    }
}
