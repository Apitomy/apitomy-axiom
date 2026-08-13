import { useState, useEffect } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
    Badge,
    Nav,
    NavExpandable,
    NavItem,
    NavList,
    PageSidebar,
    PageSidebarBody,
} from "@patternfly/react-core";
import { fetchInboxCount } from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";

const COMPONENT_PATHS = ["/action-types", "/actors", "/session-templates", "/event-sources", "/mcp-servers", "/report-definitions", "/scheduled-jobs", "/secrets", "/tools", "/toolsets"];
const SETTINGS_PATHS = ["/engine", "/manager", "/data-retention", "/configuration-packs"];

export function AppSidebar() {
    const navigate = useNavigate();
    const location = useLocation();
    const [inboxCount, setInboxCount] = useState(0);

    useEffect(() => {
        fetchInboxCount()
            .then((result) => setInboxCount(result.count))
            .catch(() => setInboxCount(0));

        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "inbox-updated" && typeof event.data.count === "number") {
                setInboxCount(event.data.count);
            }
        });
        return unsubscribe;
    }, []);

    const isComponentsActive = COMPONENT_PATHS.some(
        (p) => location.pathname === p || location.pathname.startsWith(p + "/")
    );
    const isSettingsActive = SETTINGS_PATHS.some(
        (p) => location.pathname === p || location.pathname.startsWith(p + "/")
    );

    return (
        <PageSidebar>
            <PageSidebarBody>
                <Nav>
                    <NavList>
                        <NavItem isActive={location.pathname.startsWith("/dashboards")} onClick={() => navigate("/dashboards")}>
                            Dashboards
                        </NavItem>
                        <NavItem isActive={location.pathname.startsWith("/inbox")} onClick={() => navigate("/inbox")}>
                            Inbox{" "}
                            {inboxCount > 0 && (
                                <Badge isRead={false}>{inboxCount}</Badge>
                            )}
                        </NavItem>
                        <NavItem isActive={location.pathname.startsWith("/reports")} onClick={() => navigate("/reports")}>
                            Reports
                        </NavItem>
                        <NavItem isActive={location.pathname.startsWith("/projects")} onClick={() => navigate("/projects")}>
                            Projects
                        </NavItem>
                        <NavExpandable title="Logs"
                            isActive={location.pathname.startsWith("/logs")}
                            isExpanded={location.pathname.startsWith("/logs")}>
                            <NavItem isActive={location.pathname === "/logs/activity"} onClick={() => navigate("/logs/activity")}>
                                All Activity
                            </NavItem>
                            <NavItem isActive={location.pathname === "/logs/events"} onClick={() => navigate("/logs/events")}>
                                Events
                            </NavItem>
                            <NavItem isActive={location.pathname === "/logs/manager"} onClick={() => navigate("/logs/manager")}>
                                Manager Decisions
                            </NavItem>
                            <NavItem isActive={location.pathname === "/logs/tasks"} onClick={() => navigate("/logs/tasks")}>
                                Tasks
                            </NavItem>
                            <NavItem isActive={location.pathname === "/logs/job-runs"} onClick={() => navigate("/logs/job-runs")}>
                                Job Runs
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/logs/traces")} onClick={() => navigate("/logs/traces")}>
                                Traces
                            </NavItem>
                        </NavExpandable>
                        <NavExpandable title="Metrics"
                            isActive={location.pathname.startsWith("/metrics")}
                            isExpanded={location.pathname.startsWith("/metrics")}>
                            <NavItem isActive={location.pathname === "/metrics/ai-usage"} onClick={() => navigate("/metrics/ai-usage")}>
                                AI Usage
                            </NavItem>
                            <NavItem isActive={location.pathname === "/metrics/disk-usage"} onClick={() => navigate("/metrics/disk-usage")}>
                                Disk Usage
                            </NavItem>
                        </NavExpandable>
                        <NavExpandable title="Components" isActive={isComponentsActive} isExpanded={isComponentsActive}>
                            <NavItem isActive={location.pathname.startsWith("/action-types")} onClick={() => navigate("/action-types")}>
                                Action Types
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/actors")} onClick={() => navigate("/actors")}>
                                Actors
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/session-templates")} onClick={() => navigate("/session-templates")}>
                                AI Assistant
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/event-sources")} onClick={() => navigate("/event-sources")}>
                                Event Sources
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/mcp-servers")} onClick={() => navigate("/mcp-servers")}>
                                MCP Servers
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/report-definitions")} onClick={() => navigate("/report-definitions")}>
                                Report Definitions
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/scheduled-jobs")} onClick={() => navigate("/scheduled-jobs")}>
                                Scheduled Jobs
                            </NavItem>
                            <NavItem isActive={location.pathname === "/secrets"} onClick={() => navigate("/secrets")}>
                                Secrets
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/tools") && !location.pathname.startsWith("/toolsets")} onClick={() => navigate("/tools")}>
                                Tools
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/toolsets")} onClick={() => navigate("/toolsets")}>
                                Toolsets
                            </NavItem>
                        </NavExpandable>
                        <NavExpandable title="Settings" isActive={isSettingsActive} isExpanded={isSettingsActive}>
                            <NavItem isActive={location.pathname.startsWith("/engine")} onClick={() => navigate("/engine")}>
                                AI Engine
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/manager")} onClick={() => navigate("/manager")}>
                                Manager
                            </NavItem>
                            <NavItem isActive={location.pathname === "/data-retention"} onClick={() => navigate("/data-retention")}>
                                Data Retention
                            </NavItem>
                            <NavItem isActive={location.pathname === "/configuration-packs"} onClick={() => navigate("/configuration-packs")}>
                                Configuration Packs
                            </NavItem>
                        </NavExpandable>
                    </NavList>
                </Nav>
            </PageSidebarBody>
        </PageSidebar>
    );
}
