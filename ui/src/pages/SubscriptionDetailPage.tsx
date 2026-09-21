import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Card,
    CardBody,
    CardTitle,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    HelperText,
    HelperTextItem,
    PageSection,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { LabelInput } from "../components/LabelInput";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    type Subscription,
    type NewSubscription,
    fetchSubscription,
    updateSubscription,
} from "../config/api";

export function SubscriptionDetailPage() {
    const { subscriptionId } = useParams<{ subscriptionId: string }>();
    const numericId = Number(subscriptionId);

    const [subscription, setSubscription] = useState<Subscription | null>(null);
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [enabled, setEnabled] = useState(true);
    const [labels, setLabels] = useState<string[]>([]);
    const [filterExpression, setFilterExpression] = useState("");

    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const effectiveTheme = useEffectiveTheme();

    const loadData = useCallback(() => {
        if (isNaN(numericId)) return;
        setLoading(true);
        fetchSubscription(numericId)
            .then((sub) => {
                setSubscription(sub);
                setName(sub.name);
                setDescription(sub.description || "");
                setEnabled(sub.enabled);
                setLabels(sub.labels || []);
                setFilterExpression(sub.filterExpression || "");
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [numericId]);

    useEffect(() => { loadData(); }, [loadData]);

    const markDirty = () => setDirty(true);

    const handleSave = () => {
        if (isNaN(numericId)) return;
        setSaving(true);

        const data: NewSubscription = {
            name,
            description: description || undefined,
            enabled,
            labels,
            filterExpression: filterExpression || undefined,
        };

        updateSubscription(numericId, data)
            .then((updated) => { setSubscription(updated); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!subscription) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Subscription not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/subscriptions">Subscriptions</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{subscription.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{subscription.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !name || saving} isLoading={saving}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <InfoTab
                            name={name} setName={(v) => { setName(v); markDirty(); }}
                            description={description} setDescription={(v) => { setDescription(v); markDirty(); }}
                            enabled={enabled} setEnabled={(v) => { setEnabled(v); markDirty(); }}
                            labels={labels} setLabels={(v) => { setLabels(v); markDirty(); }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Filters</TabTitleText>}>
                    <TabContent id="filters-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <Card isCompact>
                            <CardTitle>Available Fields</CardTitle>
                            <CardBody>
                                <DescriptionList isCompact isHorizontal>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.type</code></DescriptionListTerm>
                                        <DescriptionListDescription>Event type (e.g., "issue.created", "pr.merged")</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.source</code></DescriptionListTerm>
                                        <DescriptionListDescription>Source system ("github" or "jira")</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.connectionId</code></DescriptionListTerm>
                                        <DescriptionListDescription>Connection slug (e.g., "github-com")</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.ref</code></DescriptionListTerm>
                                        <DescriptionListDescription>Full URL of the event subject</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.payload.*</code></DescriptionListTerm>
                                        <DescriptionListDescription>Typed payload fields (e.g., event.payload.issue.title, event.payload.pullRequest.baseBranch)</DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm><code>event.actor.login</code></DescriptionListTerm>
                                        <DescriptionListDescription>Actor username or account ID</DescriptionListDescription>
                                    </DescriptionListGroup>
                                </DescriptionList>
                            </CardBody>
                        </Card>
                        <Card isCompact style={{ marginTop: "16px" }}>
                            <CardTitle>Examples</CardTitle>
                            <CardBody>
                                <div style={{ fontFamily: "monospace", fontSize: "13px", lineHeight: "1.8" }}>
                                    <div><code>event.type == 'issue.created'</code> — match a specific event type</div>
                                    <div><code>event.type.startsWith('pr.')</code> — match all PR events</div>
                                    <div><code>event.connectionId == 'github-com' && event.type.startsWith('issue.')</code> — combine conditions</div>
                                    <div><code>event.type == 'issue.created' || event.type == 'issue.closed'</code> — match either type</div>
                                    <div><code>event.connectionId != 'jira-staging'</code> — exclude a connection</div>
                                    <div><code>event.payload.issue.state == 'open'</code> — match on payload fields</div>
                                </div>
                            </CardBody>
                        </Card>
                        <div style={{ marginTop: "16px" }}>
                            <Title headingLevel="h4" size="md" style={{ marginBottom: "8px" }}>
                                Filter Expression
                            </Title>
                            <HelperText style={{ marginBottom: "8px" }}>
                                <HelperTextItem>
                                    Jakarta EL expression that evaluates to boolean. Leave empty to match all events.
                                </HelperTextItem>
                            </HelperText>
                            <CodeEditor
                                code={filterExpression}
                                onCodeChange={(value) => { setFilterExpression(value); setDirty(true); }}
                                language={Language.plaintext}
                                isDarkTheme={effectiveTheme === "dark"}
                                height="120px"
                                isLineNumbersVisible={false}
                            />
                        </div>
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

function InfoTab({ name, setName, description, setDescription, enabled, setEnabled, labels, setLabels }: {
    name: string;
    setName: (v: string) => void;
    description: string;
    setDescription: (v: string) => void;
    enabled: boolean;
    setEnabled: (v: boolean) => void;
    labels: string[];
    setLabels: (v: string[]) => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={name}
                    onChange={(_e, v) => setName(v)} />
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={description}
                    onChange={(_e, v) => setDescription(v)} />
            </FormGroup>
            <FormGroup fieldId="enabled">
                <Switch id="enabled" label="Enabled"
                    isChecked={enabled}
                    onChange={(_e, v) => setEnabled(v)} />
            </FormGroup>
            <FormGroup label="Labels" fieldId="labels">
                <LabelInput labels={labels} onChange={setLabels} />
            </FormGroup>
        </Form>
    );
}
