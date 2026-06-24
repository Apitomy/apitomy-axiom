import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Flex,
    FlexItem,
    PageSection,
} from "@patternfly/react-core";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import { TraceGraph } from "../components/TraceGraph";

export function TraceDetailPage() {
    const { traceId } = useParams<{ traceId: string }>();
    const [refreshKey, setRefreshKey] = useState(0);

    return (
        <PageSection>
            <Flex style={{ marginBottom: "16px" }}>
                <FlexItem grow={{ default: "grow" }}>
                    <Breadcrumb>
                        <BreadcrumbItem render={() => <Link to="/logs/traces">Traces</Link>} />
                        <BreadcrumbItem isActive>
                            {traceId?.substring(0, 8)}...
                        </BreadcrumbItem>
                    </Breadcrumb>
                </FlexItem>
                <FlexItem>
                    <Button variant="control" aria-label="Refresh"
                        onClick={() => setRefreshKey((k) => k + 1)}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>
            <div style={{ height: "calc(100vh - 160px)" }}>
                <TraceGraph traceId={traceId!} refreshKey={refreshKey} />
            </div>
        </PageSection>
    );
}
