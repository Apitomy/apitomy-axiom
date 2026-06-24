import { useParams } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    PageSection,
} from "@patternfly/react-core";
import { TraceGraph } from "../components/TraceGraph";

export function TraceDetailPage() {
    const { traceId } = useParams<{ traceId: string }>();

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem to="/logs/traces">Traces</BreadcrumbItem>
                <BreadcrumbItem isActive>
                    {traceId?.substring(0, 8)}...
                </BreadcrumbItem>
            </Breadcrumb>
            <div style={{ height: "calc(100vh - 200px)" }}>
                <TraceGraph traceId={traceId!} />
            </div>
        </PageSection>
    );
}
