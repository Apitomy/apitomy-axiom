import { useState, useEffect, useMemo } from "react";
import { useTheme, EffectiveThemeContext } from "./hooks/useTheme";
import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import { Page } from "@patternfly/react-core";

import { DashboardsPage } from "./pages/DashboardsPage";
import { DashboardViewPage } from "./pages/DashboardViewPage";
import { ProjectsPage } from "./pages/ProjectsPage";
import { ActorsPage } from "./pages/ActorsPage";
import { ActorDetailPage } from "./pages/ActorDetailPage";
import { ManagerConfigPage } from "./pages/ManagerConfigPage";
import { ActionTypesPage } from "./pages/ActionTypesPage";
import { ActivityLogPage } from "./pages/ActivityLogPage";
import { EventsPage } from "./pages/EventsPage";
import { ManagerDecisionsPage } from "./pages/ManagerDecisionsPage";
import { TasksPage } from "./pages/TasksPage";
import { EventSourcesPage } from "./pages/EventSourcesPage";
import { ProjectDetailPage } from "./pages/ProjectDetailPage";
import { ActionTypeDetailPage } from "./pages/ActionTypeDetailPage";
import { ToolsPage } from "./pages/ToolsPage";
import { McpServersPage } from "./pages/McpServersPage";
import { McpServerDetailPage } from "./pages/McpServerDetailPage";
import { ReportsPage } from "./pages/ReportsPage";
import { ReportDetailPage } from "./pages/ReportDetailPage";
import { ReportDefinitionsPage } from "./pages/ReportDefinitionsPage";
import { ReportDefinitionDetailPage } from "./pages/ReportDefinitionDetailPage";
import { AiUsagePage } from "./pages/AiUsagePage";
import { DiskUsagePage } from "./pages/DiskUsagePage";
import { ToolDetailPage } from "./pages/ToolDetailPage";
import { ToolsetsPage } from "./pages/ToolsetsPage";
import { ToolsetDetailPage } from "./pages/ToolsetDetailPage";
import { SecretsPage } from "./pages/SecretsPage";
import { AppMasthead } from "./components/AppMasthead";
import { AppSidebar } from "./components/AppSidebar";
import { ConfigurationWarning } from "./components/ConfigurationWarning";
import { ConfigurationPacksPage } from "./pages/ConfigurationPacksPage";
import { DataRetentionPage } from "./pages/DataRetentionPage";
import { EngineSettingsPage } from "./pages/EngineSettingsPage";
import { EventSourceDetailPage } from "./pages/EventSourceDetailPage";
import { AssistantPage } from "./pages/AssistantPage";
import { AssistantSessionPage } from "./pages/AssistantSessionPage";
import { TracesPage } from "./pages/TracesPage";
import { TraceDetailPage } from "./pages/TraceDetailPage";
import { InboxPage } from "./pages/InboxPage";
import { SessionTemplatesPage } from "./pages/SessionTemplatesPage";
import { SessionTemplateDetailPage } from "./pages/SessionTemplateDetailPage";
import { ScheduledJobsPage } from "./pages/ScheduledJobsPage";
import { ScheduledJobDetailPage } from "./pages/ScheduledJobDetailPage";
import { ScheduledJobRunsPage } from "./pages/ScheduledJobRunsPage";
import { type StartupCheck, fetchSystemHealth, fetchSystemConfig } from "./config/api";
import { sseClient } from "./config/sse";

export function App() {
    const location = useLocation();
    const { mode: themeMode, effectiveTheme, setMode: setThemeMode } = useTheme();
    const [startupChecks, setStartupChecks] = useState<StartupCheck[] | null>(null);
    const [engineName, setEngineName] = useState<string | undefined>(undefined);
    const [appVersion, setAppVersion] = useState<string>("");
    const themeProps = useMemo(() => ({ themeMode, setThemeMode }), [themeMode, setThemeMode]);

    useEffect(() => {
        fetchSystemHealth()
            .then(() => sseClient.connect())
            .catch(console.error);

        fetchSystemConfig()
            .then((config) => {
                if (config.checks) {
                    setStartupChecks(config.checks);
                }
                if (config.engine) {
                    setEngineName(config.engine);
                }
                if (config.version) {
                    setAppVersion(config.version);
                }
            })
            .catch(console.error);

        return () => {
            sseClient.disconnect();
        };
    }, []);

    const isAssistantPage = location.pathname.startsWith("/assistant");

    const hasCheckErrors = startupChecks != null &&
        startupChecks.some((c) => c.status === "error");
    const isBreakout = new URLSearchParams(location.search).get("breakout") === "true";

    return (
        <EffectiveThemeContext.Provider value={effectiveTheme}>
            <Page
                masthead={isBreakout ? undefined : <AppMasthead engineName={engineName} appVersion={appVersion} {...themeProps} />}
                sidebar={hasCheckErrors || isAssistantPage || isBreakout ? undefined : <AppSidebar />}
                isContentFilled
                style={isBreakout ? {
                    paddingTop: 8,
                    "--pf-v6-c-page__sidebar--Width": "0px",
                    "--pf-v6-c-page__sidebar--xl--Width": "0px",
                } as React.CSSProperties : undefined}
            >
                {hasCheckErrors ? (
                    <ConfigurationWarning checks={startupChecks!} />
                ) : (
                    <Routes>
                        <Route path="/" element={<Navigate to="/dashboards" replace />} />
                        <Route path="/dashboards" element={<DashboardsPage />} />
                        <Route path="/dashboards/:dashboardId" element={<DashboardViewPage />} />
                        <Route path="/inbox" element={<InboxPage />} />
                        <Route path="/projects" element={<ProjectsPage />} />
                        <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
                        <Route path="/engine" element={<EngineSettingsPage />} />
                        <Route path="/actors" element={<ActorsPage />} />
                        <Route path="/actors/:actorId" element={<ActorDetailPage />} />
                        <Route path="/manager" element={<ManagerConfigPage />} />
                        <Route path="/action-types" element={<ActionTypesPage />} />
                        <Route path="/action-types/:actionTypeId" element={<ActionTypeDetailPage />} />
                        <Route path="/tools" element={<ToolsPage />} />
                        <Route path="/tools/:toolId" element={<ToolDetailPage />} />
                        <Route path="/toolsets" element={<ToolsetsPage />} />
                        <Route path="/toolsets/:toolsetId" element={<ToolsetDetailPage />} />
                        <Route path="/mcp-servers" element={<McpServersPage />} />
                        <Route path="/mcp-servers/:mcpServerId" element={<McpServerDetailPage />} />
                        <Route path="/logs/activity" element={<ActivityLogPage />} />
                        <Route path="/logs/events" element={<EventsPage />} />
                        <Route path="/logs/manager" element={<ManagerDecisionsPage />} />
                        <Route path="/logs/tasks" element={<TasksPage />} />
                        <Route path="/logs/job-runs" element={<ScheduledJobRunsPage />} />
                        <Route path="/logs/traces" element={<TracesPage />} />
                        <Route path="/logs/traces/:traceId" element={<TraceDetailPage />} />
                        <Route path="/reports" element={<ReportsPage />} />
                        <Route path="/reports/:reportId" element={<ReportDetailPage />} />
                        <Route path="/report-definitions" element={<ReportDefinitionsPage />} />
                        <Route path="/report-definitions/:definitionId" element={<ReportDefinitionDetailPage />} />
                        <Route path="/metrics/ai-usage" element={<AiUsagePage />} />
                        <Route path="/metrics/disk-usage" element={<DiskUsagePage />} />
                        <Route path="/event-sources" element={<EventSourcesPage />} />
                        <Route path="/event-sources/:eventSourceId" element={<EventSourceDetailPage />} />
                        <Route path="/secrets" element={<SecretsPage />} />
                        <Route path="/configuration-packs" element={<ConfigurationPacksPage />} />
                        <Route path="/data-retention" element={<DataRetentionPage />} />
                        <Route path="/scheduled-jobs" element={<ScheduledJobsPage />} />
                        <Route path="/scheduled-jobs/:jobId" element={<ScheduledJobDetailPage />} />
                        <Route path="/session-templates" element={<SessionTemplatesPage />} />
                        <Route path="/session-templates/:templateId" element={<SessionTemplateDetailPage />} />
                        <Route path="/assistant" element={<AssistantPage />} />
                        <Route path="/assistant/:sessionId" element={<AssistantSessionPage />} />
                    </Routes>
                )}
            </Page>
        </EffectiveThemeContext.Provider>
    );
}
