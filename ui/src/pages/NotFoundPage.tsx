import { Button, EmptyState, EmptyStateActions, EmptyStateBody, EmptyStateFooter, PageSection } from "@patternfly/react-core";
import { SearchIcon } from "@patternfly/react-icons";
import { useLocation, useNavigate } from "react-router-dom";

/**
 * Shown for any URL that matches no route, instead of a blank page.
 *
 * @returns the "page not found" page
 */
export function NotFoundPage() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
        <PageSection isFilled>
            <EmptyState titleText="404: Page not found" headingLevel="h1" icon={SearchIcon}>
                <EmptyStateBody>
                    There is no page at <code>{location.pathname}</code>. It may have moved, or the link may be
                    mistyped.
                </EmptyStateBody>
                <EmptyStateFooter>
                    <EmptyStateActions>
                        <Button variant="primary" onClick={() => navigate("/runs")}>Go to Runs</Button>
                        <Button variant="link" onClick={() => navigate(-1)}>Go back</Button>
                    </EmptyStateActions>
                </EmptyStateFooter>
            </EmptyState>
        </PageSection>
    );
}
