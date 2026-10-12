import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { PageSection, Title } from "@patternfly/react-core";
import { type Composition, fetchComposition } from "../../config/compositions";

/**
 * Workbench page for a single composition. Placeholder that renders the composition name.
 */
export function WorkbenchPage() {
    const { compositionId } = useParams<{ compositionId: string }>();
    const [composition, setComposition] = useState<Composition | null>(null);

    useEffect(() => {
        fetchComposition(Number(compositionId)).then(setComposition).catch(console.error);
    }, [compositionId]);

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg">{composition?.name ?? "Loading..."}</Title>
        </PageSection>
    );
}
