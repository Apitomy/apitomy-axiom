import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
    Alert, Button, Flex, FlexItem, Form, FormGroup, HelperText, HelperTextItem, Switch, TextArea, TextInput,
    Title, Tooltip,
} from "@patternfly/react-core";
import { TrashIcon } from "@patternfly/react-icons";
import { previewSubscriptionFilter, type SubscriptionPreviewResponse } from "../config/api";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import type { ValidationMessage } from "./ActionTypeEditor";

const PREVIEW_DEBOUNCE_MS: number = 500;
const PREVIEW_LIMIT: number = 200;

type Rule = { type: string; workflowDefinitionName?: string };

/**
 * The login of an event actor, if it has one.
 *
 * @param actor the event's actor object
 * @returns the login, or undefined
 */
function actorLogin(actor: Record<string, unknown> | undefined): string | undefined {
    const login: unknown = actor?.login;
    return typeof login === "string" && login ? login : undefined;
}

/** Props of {@link SubscriptionItemEditor}. */
export interface SubscriptionItemEditorProps {
    /** The item content: `{name, description, filterExpression, routing, labels}`. */
    value: Record<string, unknown>;
    /** Called with the whole new content on every change. */
    onChange(value: Record<string, unknown>): void;
    /** Disables editing (e.g. while the AI edits the item). */
    readOnly?: boolean;
    /** Server validation problems to show above the form. */
    problems?: ValidationMessage[];
    /** Name of the composition's workflow; create-workflow rules always target it. */
    workflowName: string;
    /** Whether the item has been published (the Enabled switch is shown only then). */
    published: boolean;
    /** Whether the composition's workflow is published (the switch is disabled otherwise). */
    workflowPublished: boolean;
    /** Whether the published subscription is enabled. */
    enabled: boolean;
    /** Whether an Enabled change is being saved (the switch is disabled meanwhile). */
    enabledBusy?: boolean;
    /** Called when the user flips the Enabled switch. */
    onToggleEnabled(enabled: boolean): void;
}

/**
 * Editor for a workbench subscription item: fields, routing rules bound to the composition's workflow, a preview
 * of the recent events the filter matches, and (once published) the Enabled switch.
 */
export function SubscriptionItemEditor(props: SubscriptionItemEditorProps) {
    const { value, onChange, readOnly, problems, workflowName } = props;
    const routing: Rule[] = Array.isArray(value.routing) ? (value.routing as Rule[]) : [];
    const filter: string = (value.filterExpression as string | undefined) ?? "";
    const [preview, setPreview] = useState<SubscriptionPreviewResponse | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);

    useEffect(() => {
        const timer = setTimeout(() => {
            previewSubscriptionFilter({ filterExpression: filter, limit: PREVIEW_LIMIT })
                .then((r) => { setPreview(r); setPreviewError(null); })
                .catch((e: unknown) => setPreviewError(e instanceof Error ? e.message : String(e)));
        }, PREVIEW_DEBOUNCE_MS);
        return () => clearTimeout(timer);
    }, [filter]);

    const set = (field: string, v: unknown) => onChange({ ...value, [field]: v });
    const setRouting = (rules: Rule[]) => set("routing", rules);
    const matches = (preview?.results ?? []).filter((r) => r.matched);

    return (
        <div className="axiom-subscription-item-editor">
            {props.published && (
                <Flex justifyContent={{ default: "justifyContentFlexEnd" }}>
                    <FlexItem>
                        <Tooltip content="Publish the workflow before enabling this subscription"
                            trigger={props.workflowPublished ? "manual" : "mouseenter focus"}>
                            <Switch id="subscription-enabled" label="Enabled" aria-label="Enabled"
                                isChecked={props.enabled} isDisabled={!props.workflowPublished || !!props.enabledBusy}
                                onChange={(_e, checked) => props.onToggleEnabled(checked)} />
                        </Tooltip>
                    </FlexItem>
                </Flex>
            )}
            {(problems ?? []).length > 0 && (
                <Alert isInline variant="warning" title="Problems">
                    <ul>{(problems ?? []).map((p, i) => <li key={i}>{p.field ? `${p.field}: ` : ""}{p.message}</li>)}</ul>
                </Alert>
            )}
            <Form onSubmit={(e) => e.preventDefault()}>
                <FormGroup label="Name" fieldId="subscription-name">
                    <TextInput id="subscription-name" value={(value.name as string) ?? ""} isDisabled={readOnly}
                        onChange={(_e, v) => set("name", v)} />
                </FormGroup>
                <FormGroup label="Description" fieldId="subscription-description">
                    <TextArea id="subscription-description" value={(value.description as string) ?? ""}
                        isDisabled={readOnly} rows={2} onChange={(_e, v) => set("description", v)} />
                </FormGroup>
                <FormGroup label="Filter expression" fieldId="subscription-filter">
                    <TextArea id="subscription-filter" className="axiom-monospace" value={filter} rows={4}
                        isDisabled={readOnly} onChange={(_e, v) => set("filterExpression", v)} />
                </FormGroup>
                <FormGroup label="Routing" fieldId="subscription-routing">
                    {routing.map((rule, i) => (
                        <Flex key={i} alignItems={{ default: "alignItemsCenter" }}>
                            <FlexItem>
                                {rule.type === "create-workflow" ? `Starts workflow ${workflowName}`
                                    : "Delivers events to waiting runs (receive-event nodes)"}
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" aria-label={`Remove rule ${i + 1}`} isDisabled={readOnly}
                                    icon={<TrashIcon />} onClick={() => setRouting(routing.filter((_, j) => j !== i))} />
                            </FlexItem>
                        </Flex>
                    ))}
                    <Flex>
                        <Button variant="link" isInline isDisabled={readOnly}
                            onClick={() => setRouting([...routing,
                                { type: "create-workflow", workflowDefinitionName: workflowName }])}>
                            Add start rule
                        </Button>
                        <Button variant="link" isInline isDisabled={readOnly}
                            onClick={() => setRouting([...routing, { type: "workflow-dispatch" }])}>
                            Add dispatch rule
                        </Button>
                    </Flex>
                </FormGroup>
            </Form>
            <Title headingLevel="h3" size="md">Matching events</Title>
            {previewError && <Alert isInline variant="danger" title={`Could not preview: ${previewError}`} />}
            {preview && (
                <>
                    <p>{`${matches.length} of ${preview.results.length} recent events match`}</p>
                    {(preview.errorCount ?? 0) > 0 && (
                        <HelperText><HelperTextItem variant="warning">
                            {`${preview.errorCount} event(s) could not be evaluated: ${preview.firstError}`}
                        </HelperTextItem></HelperText>
                    )}
                    {matches.length > 0 && (
                        <Table aria-label="Matching events" variant="compact">
                            <Thead>
                                <Tr>
                                    <Th>Type</Th>
                                    <Th>Ref</Th>
                                    <Th>Actor</Th>
                                    <Th>Time</Th>
                                </Tr>
                            </Thead>
                            <Tbody>
                                {matches.map((m) => (
                                    <Tr key={m.event.id}>
                                        <Td dataLabel="Type">
                                            <Link to={`/events/stream/${m.event.id}`}>{m.event.type}</Link>
                                        </Td>
                                        <Td dataLabel="Ref" modifier="breakWord">{m.event.ref}</Td>
                                        <Td dataLabel="Actor">{actorLogin(m.event.actor) ?? "—"}</Td>
                                        <Td dataLabel="Time" modifier="nowrap">
                                            {new Date(m.event.timestamp).toLocaleString()}
                                        </Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    )}
                </>
            )}
        </div>
    );
}
