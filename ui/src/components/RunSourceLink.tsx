import { Link } from "react-router-dom";

export interface RunSourceLinkProps {
    reportId?: number;
    scheduledJobRunId?: number;
}

/**
 * Links an activity or AI usage row back to the report or scheduled job run that produced it.
 * Rows carry only the run ID (not the job ID), so job runs link to the job runs page.
 */
export function RunSourceLink({ reportId, scheduledJobRunId }: RunSourceLinkProps) {
    if (reportId != null) {
        return <Link to={`/reports/${reportId}`} title={`Report #${reportId}`}>Report #{reportId}</Link>;
    }
    if (scheduledJobRunId != null) {
        return (
            <Link to="/logs/job-runs" title={`Scheduled job run #${scheduledJobRunId}`}>
                Job run #{scheduledJobRunId}
            </Link>
        );
    }
    return <>—</>;
}
