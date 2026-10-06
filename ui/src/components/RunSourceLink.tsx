import { Link } from "react-router-dom";

export interface RunSourceLinkProps {
    scheduledJobRunId?: number;
}

/**
 * Links an activity or AI usage row back to the scheduled job run that produced it.
 * Rows carry only the run ID (not the job ID), so job runs link to the job runs page.
 */
export function RunSourceLink({ scheduledJobRunId }: RunSourceLinkProps) {
    if (scheduledJobRunId != null) {
        return (
            <Link to="/logs/job-runs" title={`Scheduled job run #${scheduledJobRunId}`}>
                Job run #{scheduledJobRunId}
            </Link>
        );
    }
    return <>—</>;
}
