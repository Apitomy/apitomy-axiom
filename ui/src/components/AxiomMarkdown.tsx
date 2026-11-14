import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Content } from "@patternfly/react-core";
import { markdownMermaidComponents } from "./MermaidBlock";
import "../axiom-markdown.css";

/** Props for {@link AxiomMarkdown}. */
export interface AxiomMarkdownProps {
    /** The raw markdown to render. Raw HTML in it is not rendered. */
    children: string;
    /** Optional extra class names for the wrapper. */
    className?: string;
    /** Optional inline styles for the wrapper. */
    style?: React.CSSProperties;
}

/**
 * Renders markdown with Axiom's standard setup: GitHub-flavored markdown, mermaid diagrams, and the
 * {@code axiom-markdown} styles. Raw HTML is never rendered, so untrusted content is safe to display.
 */
export function AxiomMarkdown({ children, className, style }: AxiomMarkdownProps) {
    return (
        <div className={className ? `axiom-markdown ${className}` : "axiom-markdown"} style={style}>
            <Content>
                <Markdown remarkPlugins={[remarkGfm]} components={markdownMermaidComponents}>
                    {children}
                </Markdown>
            </Content>
        </div>
    );
}
