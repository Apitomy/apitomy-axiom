import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
import {
    Alert,
    Card,
    CardBody,
    CardFooter,
    CardHeader,
    CardTitle,
    EmptyState,
    EmptyStateBody,
    Gallery,
    GalleryItem,
    Grid,
    GridItem,
    Label,
    PageSection,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import ArrowRightIcon from "@patternfly/react-icons/dist/esm/icons/arrow-right-icon";
import {
    type Project,
    type ActivityLogEntry,
    type Report,
    fetchProjects,
    fetchActivityLog,
    fetchActionTypes,
    fetchActors,
    fetchEventSources,
    fetchTools,
    fetchReportDefinitions,
    fetchReports,
} from "../config/api";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Created: "blue",
    InProgress: "green",
    Idle: "orange",
    Completed: "grey",
    Failed: "red",
};

const STATUS_LABELS: Record<string, string> = {
    Created: "Created",
    InProgress: "In Progress",
    Idle: "Idle",
    Completed: "Completed",
};

const ENTRY_TYPE_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    "event-received": "blue",
    "task-created": "green",
    "task-started": "green",
    "task-completed": "green",
    "task-failed": "red",
    "project-created": "blue",
    "project-closed": "grey",
    "project-reopened": "orange",
    "event-ignored": "grey",
    "manager-escalation": "orange",
    "manager-no-decision": "grey",
    "report-generating": "blue",
    "report-completed": "green",
    "report-failed": "red",
};

interface SetupRequirement {
    name: string;
    met: boolean;
    description: string;
    navPath: string;
    navLabel: string;
}

export function DashboardPage() {
    const [projects, setProjects] = useState<Project[]>([]);
    const [recentActivity, setRecentActivity] = useState<ActivityLogEntry[]>([]);
    const [recentReports, setRecentReports] = useState<Report[]>([]);
    const [loading, setLoading] = useState(true);
    const [requirements, setRequirements] = useState<SetupRequirement[]>([]);
    const [setupChecked, setSetupChecked] = useState(false);
    const [configCounts, setConfigCounts] = useState({
        actors: 0, actionTypes: 0, tools: 0, eventSources: 0, reportDefinitions: 0,
    });

    const loadData = useCallback(() => {
        setLoading(true);
        Promise.all([
            fetchProjects(1, 100),
            fetchActivityLog(1, 10),
            fetchEventSources(),
            fetchActors(),
            fetchActionTypes(),
            fetchTools(1, 1),
            fetchReportDefinitions(),
            fetchReports(1, 20),
        ])
            .then(([p, a, eventSources, actors, actionTypes, toolsResult, reportDefs, reports]) => {
                setProjects(p.items);
                setRecentActivity(a.items);
                const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
                setRecentReports(reports.items.filter((r: Report) => r.createdOn >= oneDayAgo));
                setConfigCounts({
                    actors: actors.length,
                    actionTypes: actionTypes.length,
                    tools: toolsResult.totalCount,
                    eventSources: eventSources.length,
                    reportDefinitions: reportDefs.length,
                });

                setRequirements([
                    {
                        name: "Actor",
                        met: actors.length > 0,
                        description:
                            "At least one actor (AI agent or human) must be configured to " +
                            "perform tasks assigned by the Manager.",
                        navPath: "/actors",
                        navLabel: "Configure Actors",
                    },
                    {
                        name: "Action Type",
                        met: actionTypes.length > 0,
                        description:
                            "At least one action type must be registered to define the kinds " +
                            "of work that can be performed.",
                        navPath: "/action-types",
                        navLabel: "Configure Action Types",
                    },
                ]);
                setSetupChecked(true);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const unmetRequirements = requirements.filter((r) => !r.met);
    const setupIncomplete = setupChecked && unmetRequirements.length > 0;

    const statusCounts = projects.reduce(
        (acc, p) => {
            acc[p.status] = (acc[p.status] || 0) + 1;
            return acc;
        },
        {} as Record<string, number>
    );

    const activeProjects = projects.filter((p) => p.status !== "Completed");

    if (setupIncomplete) {
        return (
            <PageSection>
                <Title headingLevel="h1" size="lg">Dashboard</Title>
                <Alert
                    variant="warning"
                    title="Setup Incomplete"
                    isInline
                    style={{ marginTop: "16px" }}
                >
                    <p style={{ marginBottom: "12px" }}>
                        The following configuration is required before the system can
                        process events and perform work:
                    </p>
                    <table style={{ width: "100%", borderCollapse: "collapse" }}>
                        <tbody>
                            {requirements.map((req) => (
                                <tr key={req.name} style={{ borderBottom: "1px solid var(--pf-t--global--border--color--default, #d2d2d2)" }}>
                                    <td style={{ padding: "8px", width: "24px" }}>
                                        {req.met ? (
                                            <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" />
                                        ) : (
                                            <ExclamationCircleIcon color="var(--pf-t--global--color--status--warning--default)" />
                                        )}
                                    </td>
                                    <td style={{ padding: "8px", fontWeight: "bold", width: "120px" }}>
                                        {req.name}
                                    </td>
                                    <td style={{ padding: "8px" }}>
                                        {req.met ? (
                                            <span style={{ color: "var(--pf-t--global--color--status--success--default)" }}>
                                                Configured
                                            </span>
                                        ) : (
                                            <>
                                                {req.description}{" "}
                                                <Link to={req.navPath}>{req.navLabel}</Link>
                                            </>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </Alert>
            </PageSection>
        );
    }

    return (
        <PageSection isFilled={true}>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Dashboard
            </Title>

            <Grid hasGutter={true}>
                <GridItem span={8}>
                    {/* Gallery of Project Stats */}
                    <Gallery hasGutter minWidths={{ default: "150px" }} style={{ marginBottom: "16px" }}>
                        <GalleryItem>
                            <StatusCard label="Total Projects" count={projects.length} />
                        </GalleryItem>
                        {["InProgress", "Idle", "Created", "Completed"].map((status) => (
                            <GalleryItem key={status}>
                                <StatusCard
                                    label={STATUS_LABELS[status] || status}
                                    count={statusCounts[status] || 0}
                                    color={STATUS_COLORS[status]}
                                />
                            </GalleryItem>
                        ))}
                    </Gallery>

                    { /* Active Projects */ }
                    <Card>
                        <CardHeader>
                            <CardTitle>Active Projects</CardTitle>
                        </CardHeader>
                        <CardBody isFilled={false}>
                            {loading ? (
                                <EmptyState variant="xs">
                                    <EmptyStateBody>Loading...</EmptyStateBody>
                                </EmptyState>
                            ) : activeProjects.length === 0 ? (
                                <EmptyState variant="xs">
                                    <EmptyStateBody>
                                        No active projects. Create a GitHub issue on a
                                        monitored repository to get started.
                                    </EmptyStateBody>
                                </EmptyState>
                            ) : (
                                <Table aria-label="Active Projects" variant="compact">
                                    <Thead>
                                        <Tr>
                                            <Th>Name</Th>
                                            <Th>Status</Th>
                                            <Th>Issue</Th>
                                            <Th>Updated</Th>
                                        </Tr>
                                    </Thead>
                                    <Tbody>
                                        {activeProjects.slice(0, 8).map((project) => (
                                            <Tr key={project.id} isClickable>
                                                <Td>
                                                    <Link to={`/projects/${project.id}`}>
                                                        {project.name}
                                                    </Link>
                                                </Td>
                                                <Td>
                                                    <Label isCompact
                                                           color={STATUS_COLORS[project.status] || "grey"}>
                                                        {STATUS_LABELS[project.status] || project.status}
                                                    </Label>
                                                </Td>
                                                <Td>{project.issueRef}</Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {new Date(project.updatedOn).toLocaleString()}
                                                </Td>
                                            </Tr>
                                        ))}
                                    </Tbody>
                                </Table>
                            )}
                        </CardBody>
                        {activeProjects.length > 0 && (
                            <CardFooter>
                                <Link to="/projects">
                                    View all projects <ArrowRightIcon />
                                </Link>
                            </CardFooter>
                        )}
                    </Card>

                    {/* Recent Reports */}
                    <Card style={{ marginTop: "16px" }}>
                        <CardHeader>
                            <CardTitle>Recent Reports</CardTitle>
                        </CardHeader>
                        <CardBody isFilled={false}>
                            {recentReports.length === 0 ? (
                                <EmptyState variant="xs">
                                    <EmptyStateBody>No reports in the last 24 hours.</EmptyStateBody>
                                </EmptyState>
                            ) : (
                                <Table aria-label="Recent Reports" variant="compact">
                                    <Thead>
                                        <Tr>
                                            <Th>Title</Th>
                                            <Th>Status</Th>
                                            <Th>Duration</Th>
                                            <Th>Cost</Th>
                                            <Th>Generated</Th>
                                        </Tr>
                                    </Thead>
                                    <Tbody>
                                        {recentReports.map((report) => (
                                            <Tr key={report.id} isClickable>
                                                <Td>
                                                    <Link to={`/reports/${report.id}`}>
                                                        {report.title || `Report #${report.id}`}
                                                    </Link>
                                                </Td>
                                                <Td>
                                                    <Label isCompact color={
                                                        report.status === "Completed" ? "green"
                                                            : report.status === "Failed" ? "red" : "blue"
                                                    }>
                                                        {report.status}
                                                    </Label>
                                                </Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {report.durationMs != null ? formatDuration(report.durationMs) : "—"}
                                                </Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {report.costUsd != null ? `$${report.costUsd.toFixed(4)}` : "—"}
                                                </Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {new Date(report.createdOn).toLocaleString()}
                                                </Td>
                                            </Tr>
                                        ))}
                                    </Tbody>
                                </Table>
                            )}
                        </CardBody>
                        <CardFooter>
                            <Link to="/reports">
                                View all reports <ArrowRightIcon />
                            </Link>
                        </CardFooter>
                    </Card>

                    {/* Configuration summary */}
                    <Card style={{ marginTop: "16px" }}>
                        <CardHeader>
                            <CardTitle>Configuration</CardTitle>
                        </CardHeader>
                        <CardBody>
                            <Gallery hasGutter minWidths={{ default: "140px" }}>
                                <GalleryItem>
                                    <ConfigCard label="Event Sources" count={configCounts.eventSources}
                                                path="/event-sources" />
                                </GalleryItem>
                                <GalleryItem>
                                    <ConfigCard label="Actors" count={configCounts.actors}
                                                path="/actors" />
                                </GalleryItem>
                                <GalleryItem>
                                    <ConfigCard label="Action Types" count={configCounts.actionTypes}
                                                path="/action-types" />
                                </GalleryItem>
                                <GalleryItem>
                                    <ConfigCard label="Tools" count={configCounts.tools}
                                                path="/tools" />
                                </GalleryItem>
                                <GalleryItem>
                                    <ConfigCard label="Report Definitions" count={configCounts.reportDefinitions}
                                                path="/report-definitions" />
                                </GalleryItem>
                            </Gallery>
                        </CardBody>
                    </Card>
                </GridItem>
                <GridItem span={4}>
                    <Card isFullHeight>
                        <CardHeader>
                            <CardTitle>Recent Activity</CardTitle>
                        </CardHeader>
                        <CardBody style={{ maxHeight: "400px", overflowY: "auto" }}>
                            {recentActivity.length === 0 ? (
                                <EmptyState variant="xs">
                                    <EmptyStateBody>No recent activity.</EmptyStateBody>
                                </EmptyState>
                            ) : (
                                <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                                    {recentActivity.map((entry) => (
                                        <div key={entry.id} style={{
                                            padding: "8px 0",
                                            borderBottom: "1px solid var(--pf-t--global--border--color--default)",
                                        }}>
                                            <div style={{
                                                display: "flex",
                                                justifyContent: "space-between",
                                                alignItems: "center",
                                                marginBottom: "4px",
                                            }}>
                                                <Label isCompact
                                                       color={ENTRY_TYPE_COLORS[entry.entryType] || "grey"}>
                                                    {entry.entryType}
                                                </Label>
                                                <span className="axiom-text-subtle" style={{ fontSize: "12px" }}>
                                                    {new Date(entry.createdOn).toLocaleTimeString()}
                                                </span>
                                            </div>
                                            <div className="axiom-text-default" style={{ fontSize: "13px" }}>
                                                {entry.summary.length > 120
                                                    ? entry.summary.substring(0, 117) + "..."
                                                    : entry.summary}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </CardBody>
                        <CardFooter>
                            <Link to="/logs/activity">
                                View full activity log <ArrowRightIcon />
                            </Link>
                        </CardFooter>
                    </Card>

                </GridItem>
            </Grid>

        </PageSection>
    );
}

function StatusCard({ label, count, color }: {
    label: string;
    count: number;
    color?: string;
}) {
    return (
        <Card isCompact isFullHeight>
            <CardBody style={{ textAlign: "center", padding: "16px" }}>
                <div style={{
                    fontSize: "28px",
                    fontWeight: "bold",
                    color: color ? `var(--pf-t--global--color--status--${color}--default, inherit)` : "inherit",
                }}>
                    {count}
                </div>
                <div className="axiom-text-subtle" style={{ fontSize: "13px", marginTop: "4px" }}>
                    {label}
                </div>
            </CardBody>
        </Card>
    );
}

function ConfigCard({ label, count, path }: {
    label: string;
    count: number;
    path: string;
}) {
    return (
        <div style={{ textAlign: "center" }}>
            <div style={{ fontSize: "22px", fontWeight: "bold" }}>{count}</div>
            <div style={{ fontSize: "13px", marginTop: "2px" }}>
                <Link to={path}>{label}</Link>
            </div>
        </div>
    );
}

function formatDuration(ms: number): string {
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (minutes === 0) return `${seconds}s`;
    return `${minutes}m ${seconds}s`;
}
