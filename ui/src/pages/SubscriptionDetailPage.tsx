import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Card,
    CardBody,
    CardTitle,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
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
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import { LabelInput } from "../components/LabelInput";
import {
    type Subscription,
    type NewSubscription,
    type EventSourceFilterRule,
    type EventSourceFilters,
    fetchSubscription,
    updateSubscription,
} from "../config/api";

type RuleType = EventSourceFilterRule["type"];

const RULE_TYPE_OPTIONS: { value: RuleType; label: string }[] = [
    { value: "event-type", label: "Event Type" },
    { value: "payload", label: "Payload" },
    { value: "connection", label: "Connection" },
    { value: "ref", label: "Ref" },
];

export function SubscriptionDetailPage() {
    const { subscriptionId } = useParams<{ subscriptionId: string }>();
    const numericId = Number(subscriptionId);

    const [subscription, setSubscription] = useState<Subscription | null>(null);
    const [name, setName] = useState("");
    const [description, setDescription] = useState("");
    const [enabled, setEnabled] = useState(true);
    const [labels, setLabels] = useState<string[]>([]);
    const [includeRules, setIncludeRules] = useState<EventSourceFilterRule[]>([]);
    const [excludeRules, setExcludeRules] = useState<EventSourceFilterRule[]>([]);

    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

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
                setIncludeRules(sub.filters?.include || []);
                setExcludeRules(sub.filters?.exclude || []);
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

        const filters: EventSourceFilters = {
            include: includeRules,
            exclude: excludeRules,
        };

        const data: NewSubscription = {
            name,
            description: description || undefined,
            enabled,
            labels,
            filters,
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
                        <FiltersTab
                            includeRules={includeRules}
                            setIncludeRules={(rules) => { setIncludeRules(rules); markDirty(); }}
                            excludeRules={excludeRules}
                            setExcludeRules={(rules) => { setExcludeRules(rules); markDirty(); }}
                        />
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

function FiltersTab({ includeRules, setIncludeRules, excludeRules, setExcludeRules }: {
    includeRules: EventSourceFilterRule[];
    setIncludeRules: (rules: EventSourceFilterRule[]) => void;
    excludeRules: EventSourceFilterRule[];
    setExcludeRules: (rules: EventSourceFilterRule[]) => void;
}) {
    return (
        <div style={{ display: "flex", flexDirection: "column", gap: "24px" }}>
            <FilterRulesCard
                title="Include Rules"
                rules={includeRules}
                onChange={setIncludeRules}
            />
            <FilterRulesCard
                title="Exclude Rules"
                rules={excludeRules}
                onChange={setExcludeRules}
            />
        </div>
    );
}

function FilterRulesCard({ title, rules, onChange }: {
    title: string;
    rules: EventSourceFilterRule[];
    onChange: (rules: EventSourceFilterRule[]) => void;
}) {
    const addRule = () => {
        onChange([...rules, { type: "event-type", pattern: "" }]);
    };

    const updateRule = (index: number, updates: Partial<EventSourceFilterRule>) => {
        const updated = rules.map((rule, i) => {
            if (i !== index) return rule;
            const newRule = { ...rule, ...updates };
            // Clear pointer when type is not payload
            if (updates.type && updates.type !== "payload") {
                delete newRule.pointer;
            }
            return newRule;
        });
        onChange(updated);
    };

    const removeRule = (index: number) => {
        onChange(rules.filter((_, i) => i !== index));
    };

    return (
        <Card>
            <CardTitle>{title}</CardTitle>
            <CardBody>
                {rules.length === 0 ? (
                    <EmptyState variant="xs">
                        <EmptyStateBody>No rules configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label={title} variant="compact">
                        <Thead>
                            <Tr>
                                <Th width={20}>Type</Th>
                                <Th width={20}>Pointer</Th>
                                <Th width={45}>Pattern</Th>
                                <Th width={15} />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {rules.map((rule, index) => (
                                <Tr key={index}>
                                    <Td>
                                        <FormSelect
                                            value={rule.type}
                                            onChange={(_e, v) => updateRule(index, { type: v as RuleType })}
                                            aria-label="Rule type">
                                            {RULE_TYPE_OPTIONS.map((opt) => (
                                                <FormSelectOption key={opt.value} value={opt.value} label={opt.label} />
                                            ))}
                                        </FormSelect>
                                    </Td>
                                    <Td>
                                        {rule.type === "payload" ? (
                                            <TextInput
                                                value={rule.pointer || ""}
                                                onChange={(_e, v) => updateRule(index, { pointer: v })}
                                                placeholder="/issue/state"
                                                aria-label="JSON Pointer" />
                                        ) : (
                                            <span style={{ color: "var(--pf-t--global--color--nonstatus--gray--default)" }}>--</span>
                                        )}
                                    </Td>
                                    <Td>
                                        <TextInput
                                            value={rule.pattern}
                                            onChange={(_e, v) => updateRule(index, { pattern: v })}
                                            placeholder="e.g. issue.* or pr.merged"
                                            aria-label="Glob pattern" />
                                        <HelperText>
                                            <HelperTextItem>Glob pattern (* and ? wildcards)</HelperTextItem>
                                        </HelperText>
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={() => removeRule(index)}
                                            aria-label="Remove rule">
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
                <Button variant="link" icon={<PlusCircleIcon />}
                    onClick={addRule} style={{ marginTop: "8px" }}>
                    Add Rule
                </Button>
            </CardBody>
        </Card>
    );
}
