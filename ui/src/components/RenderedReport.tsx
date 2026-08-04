import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Content } from "@patternfly/react-core";
import { markdownMermaidComponents } from "./MermaidBlock";
import "./RenderedReport.css";

interface RenderedReportProps {
    content: string;
}

/**
 * Renders a generated report's markdown content with report-specific styling.
 * Wraps the output in a scoped CSS class so styles don't leak to other
 * markdown renderers in the application.
 */
export function RenderedReport({ content }: RenderedReportProps) {
    return (
        <div className="axiom-rendered-report">
            <Content>
                <Markdown remarkPlugins={[remarkGfm]} components={markdownMermaidComponents}>{content}</Markdown>
            </Content>
        </div>
    );
}
