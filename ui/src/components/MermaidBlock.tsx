import { isValidElement, useEffect, useState, type ComponentPropsWithoutRef } from "react";
import mermaid from "mermaid";

let initialized = false;
let counter = 0;

function ensureInitialized(): void {
    if (!initialized) {
        mermaid.initialize({ startOnLoad: false, theme: "default" });
        initialized = true;
    }
}

interface MermaidBlockProps {
    chart: string;
}

/**
 * Renders a Mermaid diagram from its text definition by calling
 * mermaid.render() and injecting the resulting SVG.
 */
export function MermaidBlock({ chart }: MermaidBlockProps) {
    const [svg, setSvg] = useState("");
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        ensureInitialized();
        let cancelled = false;
        const id = `mermaid-${counter++}`;

        mermaid.render(id, chart)
            .then(({ svg: rendered }) => {
                if (!cancelled) {
                    setSvg(rendered);
                    setError(null);
                }
            })
            .catch((err) => {
                if (!cancelled) {
                    setError(String(err));
                    setSvg("");
                }
            });

        return () => { cancelled = true; };
    }, [chart]);

    if (error) {
        return (
            <pre>
                <code>{chart}</code>
            </pre>
        );
    }

    if (!svg) {
        return null;
    }

    return <div dangerouslySetInnerHTML={{ __html: svg }} />;
}

/**
 * Custom component overrides for react-markdown that intercept fenced
 * code blocks with language "mermaid" and render them as interactive
 * SVG diagrams via the MermaidBlock component.
 */
export const markdownMermaidComponents = {
    pre({ children, ...props }: ComponentPropsWithoutRef<"pre">) {
        const child = Array.isArray(children) ? children[0] : children;
        if (isValidElement(child)) {
            const childProps = child.props as Record<string, unknown>;
            const className = childProps.className;
            if (typeof className === "string" && className.includes("language-mermaid")) {
                return <MermaidBlock chart={String(childProps.children).replace(/\n$/, "")} />;
            }
        }
        return <pre {...props}>{children}</pre>;
    },
};
