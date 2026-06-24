import { useNavigate, useLocation } from "react-router-dom";
import {
    Nav,
    NavExpandable,
    NavItem,
    NavList,
    PageSidebar,
    PageSidebarBody,
} from "@patternfly/react-core";

const CONFIG_PATHS = ["/actors", "/manager", "/action-types", "/tools", "/toolsets", "/mcp-servers", "/secrets", "/event-sources", "/report-definitions", "/engine", "/configuration-packs"];

export function AppSidebar() {
    const navigate = useNavigate();
    const location = useLocation();

    const isConfigActive = CONFIG_PATHS.some(
        (p) => location.pathname === p || location.pathname.startsWith(p + "/")
    );

    return (
        <PageSidebar>
            <PageSidebarBody>
                <Nav>
                    <NavList>
                        <NavItem isActive={location.pathname === "/"} onClick={() => navigate("/")}>
                            Dashboard
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
                        <NavExpandable title="Configuration" isActive={isConfigActive} isExpanded={isConfigActive}>
                            <NavItem isActive={location.pathname.startsWith("/engine")} onClick={() => navigate("/engine")}>
                                AI Engine
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/action-types")} onClick={() => navigate("/action-types")}>
                                Action Types
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/actors")} onClick={() => navigate("/actors")}>
                                Actors
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/manager")} onClick={() => navigate("/manager")}>
                                Manager
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/mcp-servers")} onClick={() => navigate("/mcp-servers")}>
                                MCP Servers
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/report-definitions")} onClick={() => navigate("/report-definitions")}>
                                Report Definitions
                            </NavItem>
                            <NavItem isActive={location.pathname.startsWith("/event-sources")} onClick={() => navigate("/event-sources")}>
                                Event Sources
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
