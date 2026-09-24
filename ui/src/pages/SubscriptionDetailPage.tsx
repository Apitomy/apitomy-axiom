import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    ClipboardCopy,
    ClipboardCopyVariant,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    HelperText,
    HelperTextItem,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    PageSection,
    Pagination,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import TimesCircleIcon from "@patternfly/react-icons/dist/esm/icons/times-circle-icon";
import HelpIcon from "@patternfly/react-icons/dist/esm/icons/help-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import { CodeEditor } from "@patternfly/react-code-editor";
import type * as Monaco from "monaco-editor";
import { ObjectSelect } from "@apitomy/common-ui-components";
import { LabelInput } from "../components/LabelInput";
import { StreamEventDetailModal } from "../components/StreamEventDetailModal";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    type ActionType,
    type NewSubscription,
    type RoutingRule,
    type StreamEvent,
    type Subscription,
    type SubscriptionPreviewResult,
    type WorkflowDefinition,
    fetchActionTypes,
    fetchSubscription,
    fetchWorkflowDefinitions,
    previewSubscriptionFilter,
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
    const [routing, setRouting] = useState<RoutingRule[]>([]);
    const [processEventsFrom, setProcessEventsFrom] = useState("");

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
                setRouting(sub.routing || []);
                if (sub.processEventsFrom) {
                    // Convert UTC to local time for the datetime-local input.
                    // datetime-local interprets its value as local time, so we
                    // must format using local accessors (getHours, etc.), not
                    // toISOString() which always produces UTC.
                    const d = new Date(sub.processEventsFrom);
                    const pad = (n: number) => String(n).padStart(2, "0");
                    setProcessEventsFrom(
                        `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
                    );
                }
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
            routing: routing.length > 0 ? routing : undefined,
            processEventsFrom: processEventsFrom
                ? new Date(processEventsFrom).toISOString().replace(/\.\d{3}Z$/, "Z")
                : undefined,
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
                <BreadcrumbItem><Link to="/events/subscriptions">Subscriptions</Link></BreadcrumbItem>
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
                            processEventsFrom={processEventsFrom}
                            setProcessEventsFrom={(v) => { setProcessEventsFrom(v); markDirty(); }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Filter</TabTitleText>}>
                    <TabContent id="filter-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <FilterTab
                            filterExpression={filterExpression}
                            setFilterExpression={(v) => { setFilterExpression(v); setDirty(true); }}
                            effectiveTheme={effectiveTheme}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Routing</TabTitleText>}>
                    <TabContent id="routing-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <RoutingTab
                            routing={routing}
                            setRouting={(v) => { setRouting(v); setDirty(true); }}
                        />
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

// ── Filter Tab (expression editor + live preview) ───────────────

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

function formatRef(ref?: string): string {
    if (!ref) return "—";
    try {
        const url = new URL(ref);
        const ghMatch = url.pathname.match(/^\/([^/]+\/[^/]+)\/(?:issues|pull)\/(\d+)/);
        if (ghMatch) return `${ghMatch[1]}#${ghMatch[2]}`;
        const jiraMatch = url.pathname.match(/\/browse\/([A-Z][A-Z0-9_]+-\d+)/);
        if (jiraMatch) return jiraMatch[1];
    } catch {
        // Not a valid URL
    }
    if (ref.length > 50) return `...${ref.slice(-47)}`;
    return ref;
}

const DEBOUNCE_MS = 600;

const MATCH_BG_LIGHT = "rgba(56, 134, 53, 0.1)";
const MATCH_BG_DARK = "rgba(56, 134, 53, 0.2)";

const EL_LANGUAGE_ID = "axiom-el";
let elLanguageRegistered = false;

/**
 * Registers a custom Monaco language for EL filter expressions.
 * Uses the Monarch tokenizer for declarative syntax highlighting.
 */
function registerElLanguage(monaco: typeof Monaco) {
    if (elLanguageRegistered) return;
    elLanguageRegistered = true;

    monaco.languages.register({ id: EL_LANGUAGE_ID });

    // Custom themes that extend the defaults with operator coloring
    monaco.editor.defineTheme("axiom-el-light", {
        base: "vs",
        inherit: true,
        rules: [
            { token: "operator.el", foreground: "811f3f" },
            { token: "delimiter.el", foreground: "505050" },
        ],
        colors: {},
    });
    monaco.editor.defineTheme("axiom-el-dark", {
        base: "vs-dark",
        inherit: true,
        rules: [
            { token: "operator.el", foreground: "d4759a" },
            { token: "delimiter.el", foreground: "a0a0a0" },
        ],
        colors: {},
    });

    monaco.languages.setMonarchTokensProvider(EL_LANGUAGE_ID, {
        defaultToken: "",
        tokenPostfix: ".el",

        keywords: ["true", "false", "null", "empty", "not", "and", "or",
                    "eq", "ne", "lt", "gt", "le", "ge", "instanceof"],

        operators: ["==", "!=", "&&", "||", "!", ">=", "<=", ">", "<", "?", ":"],

        symbols: /[=><!~?:&|+\-*/^%]+/,

        tokenizer: {
            root: [
                // String literals (single-quoted)
                [/'[^']*'/, "string"],
                // String literals (double-quoted)
                [/"[^"]*"/, "string"],

                // Numbers
                [/\d+(\.\d+)?/, "number"],

                // Identifiers and keywords
                [/[a-zA-Z_]\w*/, {
                    cases: {
                        "@keywords": "keyword",
                        "@default": "identifier",
                    },
                }],

                // Dot accessor
                [/\./, "delimiter"],

                // Operators
                [/@symbols/, {
                    cases: {
                        "@operators": "operator",
                        "@default": "",
                    },
                }],

                // Parentheses
                [/[()]/, "delimiter.parenthesis"],

                // Whitespace
                [/\s+/, "white"],
            ],
        },
    } as Monaco.languages.IMonarchLanguage);

    // ── Context-aware code completion ────────────────────────────

    type SchemaNode = { [key: string]: SchemaNode | string };

    const actorFields: SchemaNode = {
        login: "Username or account ID",
        displayName: "Display name",
        avatarUrl: "Profile image URL",
        url: "Profile URL",
    };

    const issueFields: SchemaNode = {
        number: "Issue number or key (e.g., \"123\", \"PROJ-456\")",
        title: "Issue title",
        body: "Issue description",
        state: "Normalized state: \"open\" or \"closed\"",
        stateDetail: "Source-specific state (e.g., \"In Progress\")",
        author: actorFields,
        assignees: "Current assignees (array)",
        labels: "Label names (array)",
        milestone: "Milestone or sprint name",
        url: "HTML URL to the issue",
        createdAt: "Creation timestamp (ISO-8601)",
        updatedAt: "Last update timestamp",
        closedAt: "Closure timestamp",
    };

    const pullRequestFields: SchemaNode = {
        ...issueFields,
        headBranch: "Source branch name",
        baseBranch: "Target branch name",
        headSha: "Latest commit SHA on head",
        isDraft: "Whether the PR is a draft",
        isMerged: "Whether the PR has been merged",
        mergedAt: "Merge timestamp",
        mergedBy: actorFields,
        additions: "Lines added",
        deletions: "Lines deleted",
        changedFiles: "Number of changed files",
    };

    const commentFields: SchemaNode = {
        id: "Comment ID",
        body: "Comment body text",
        author: actorFields,
        url: "HTML URL to the comment",
        createdAt: "Creation timestamp",
        updatedAt: "Last edit timestamp",
    };

    const reviewFields: SchemaNode = {
        id: "Review ID",
        state: "Review state: \"approved\", \"changes_requested\", \"commented\", \"dismissed\"",
        body: "Review body text",
        author: actorFields,
        url: "HTML URL to the review",
        submittedAt: "Submission timestamp",
    };

    const labelFields: SchemaNode = {
        name: "Label name",
        color: "Hex color code",
        description: "Label description",
    };

    const changeFields: SchemaNode = {
        field: "Which field changed",
        from: "Previous value",
        to: "New value",
        author: actorFields,
    };

    const payloadFields: SchemaNode = {
        issue: issueFields,
        pullRequest: pullRequestFields,
        comment: commentFields,
        review: reviewFields,
        label: labelFields,
        assignee: actorFields,
        change: changeFields,
        requestedReviewer: actorFields,
        ref: "Git ref (push/branch/tag events)",
        branch: "Branch name (push events)",
        beforeSha: "SHA before push",
        afterSha: "SHA after push",
        forced: "Whether push was forced",
        commits: "Commits array (push events)",
        tagName: "Tag name (release events)",
        name: "Release name (release events)",
        body: "Release notes (release events)",
        before: "Previous head SHA (pr.synchronize)",
        after: "New head SHA (pr.synchronize)",
    };

    const completionSchema: SchemaNode = {
        event: {
            type: "Normalized event type (e.g., \"issue.created\", \"pr.merged\")",
            source: "Source system: \"github\" or \"jira\"",
            connectionId: "Connection slug (e.g., \"github-com\")",
            ref: "Full URL of the event subject",
            timestamp: "When the event occurred (ISO-8601)",
            actor: actorFields,
            payload: payloadFields,
        },
    };

    function resolveSchema(path: string[]): SchemaNode | null {
        let node: SchemaNode = completionSchema;
        for (const segment of path) {
            const child = node[segment];
            if (child == null || typeof child === "string") return null;
            node = child;
        }
        return node;
    }

    monaco.languages.registerCompletionItemProvider(EL_LANGUAGE_ID, {
        triggerCharacters: ["."],
        provideCompletionItems(model, position) {
            const textUntilPosition = model.getValueInRange({
                startLineNumber: position.lineNumber,
                startColumn: 1,
                endLineNumber: position.lineNumber,
                endColumn: position.column,
            });

            // Extract the dot-separated path before the cursor
            const match = textUntilPosition.match(/([\w.]+)\.$/);
            const pathStr = match ? match[1] : "";
            const path = pathStr ? pathStr.split(".") : [];

            const node = path.length > 0 ? resolveSchema(path) : completionSchema;
            if (!node) return { suggestions: [] };

            const word = model.getWordUntilPosition(position);
            const range: Monaco.IRange = {
                startLineNumber: position.lineNumber,
                endLineNumber: position.lineNumber,
                startColumn: word.startColumn,
                endColumn: word.endColumn,
            };

            const suggestions: Monaco.languages.CompletionItem[] = Object.entries(node).map(
                ([key, value]) => {
                    const isLeaf = typeof value === "string";
                    const detail = isLeaf ? value : "(object)";
                    return {
                        label: key,
                        kind: isLeaf
                            ? monaco.languages.CompletionItemKind.Field
                            : monaco.languages.CompletionItemKind.Module,
                        insertText: key,
                        range,
                        detail,
                        sortText: key,
                    };
                }
            );

            // Add string method suggestions when the path resolves to a string field
            const lastSegment = path[path.length - 1];
            const parentPath = path.slice(0, -1);
            const parent = parentPath.length > 0 ? resolveSchema(parentPath) : completionSchema;
            if (parent && lastSegment && typeof parent[lastSegment] === "string") {
                const methods = [
                    { name: "startsWith", detail: "String prefix match", snippet: "startsWith('$1')" },
                    { name: "endsWith", detail: "String suffix match", snippet: "endsWith('$1')" },
                    { name: "contains", detail: "String substring match", snippet: "contains('$1')" },
                    { name: "isEmpty", detail: "Check if string is empty", snippet: "isEmpty()" },
                    { name: "length", detail: "String length", snippet: "length()" },
                ];
                for (const m of methods) {
                    suggestions.push({
                        label: m.name,
                        kind: monaco.languages.CompletionItemKind.Method,
                        insertText: m.snippet,
                        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                        range,
                        detail: m.detail,
                        sortText: `~${m.name}`,
                    });
                }
            }

            return { suggestions };
        },
    });
}

function FilterTab({ filterExpression, setFilterExpression, effectiveTheme }: {
    filterExpression: string;
    setFilterExpression: (v: string) => void;
    effectiveTheme: "light" | "dark";
}) {
    const matchBg = effectiveTheme === "dark" ? MATCH_BG_DARK : MATCH_BG_LIGHT;
    const [results, setResults] = useState<SubscriptionPreviewResult[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [totalMatched, setTotalMatched] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [selectedEvent, setSelectedEvent] = useState<StreamEvent | null>(null);
    const [helpOpen, setHelpOpen] = useState(false);

    // Debounce timer for auto-refresh
    const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const loadPreview = useCallback(() => {
        setPreviewLoading(true);
        previewSubscriptionFilter({
            filterExpression: filterExpression || "",
            page,
            limit: perPage,
        })
            .then((resp) => {
                setResults(resp.results);
                setTotalCount(resp.totalCount);
                setTotalMatched(resp.totalMatched);
            })
            .catch(console.error)
            .finally(() => setPreviewLoading(false));
    }, [filterExpression, page, perPage]);

    // Auto-refresh preview when expression changes (debounced)
    useEffect(() => {
        if (debounceRef.current) clearTimeout(debounceRef.current);
        debounceRef.current = setTimeout(() => {
            loadPreview();
        }, DEBOUNCE_MS);
        return () => {
            if (debounceRef.current) clearTimeout(debounceRef.current);
        };
    }, [filterExpression, page, perPage]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div>
            {/* Expression editor */}
            <Flex alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "8px", gap: "8px" }}>
                <FlexItem flex={{ default: "flex_1" }}>
                    <Title headingLevel="h4" size="md">Filter Expression</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Filter expression help"
                        onClick={() => setHelpOpen(true)}>
                        <HelpIcon />
                    </Button>
                </FlexItem>
            </Flex>
            <HelperText style={{ marginBottom: "8px" }}>
                <HelperTextItem>
                    EL expression that evaluates to boolean. Leave empty to match all events.
                </HelperTextItem>
            </HelperText>
            <CodeEditor
                code={filterExpression}
                onCodeChange={(value) => setFilterExpression(value)}
                language={EL_LANGUAGE_ID as never}
                isDarkTheme={effectiveTheme === "dark"}
                height="80px"
                isLineNumbersVisible={false}
                onEditorDidMount={(_editor, monaco) => {
                    registerElLanguage(monaco);
                    const themeName = effectiveTheme === "dark" ? "axiom-el-dark" : "axiom-el-light";
                    monaco.editor.setTheme(themeName);
                }}
                options={{
                    theme: effectiveTheme === "dark" ? "axiom-el-dark" : "axiom-el-light",
                    glyphMargin: false,
                    folding: false,
                    lineDecorationsWidth: 0,
                    lineNumbersMinChars: 0,
                    minimap: { enabled: false },
                    overviewRulerLanes: 0,
                    scrollBeyondLastLine: false,
                    renderLineHighlight: "none",
                    padding: { top: 8, bottom: 8 },
                }}
            />

            {/* Summary stats */}
            <Flex style={{ marginTop: "24px", marginBottom: "8px", gap: "24px" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h4" size="md">Preview</Title>
                </FlexItem>
                <FlexItem>
                    <strong>Total:</strong> {totalCount}
                </FlexItem>
                <FlexItem>
                    <strong>Matched:</strong>{" "}
                    <Label isCompact color="green">{totalMatched}</Label>
                </FlexItem>
                <FlexItem>
                    <strong>Excluded:</strong>{" "}
                    <Label isCompact color="red">{totalCount - totalMatched}</Label>
                </FlexItem>
                {!filterExpression && (
                    <FlexItem>
                        <HelperText>
                            <HelperTextItem variant="warning">
                                No filter expression — all events match.
                            </HelperTextItem>
                        </HelperText>
                    </FlexItem>
                )}
            </Flex>

            {/* Toolbar */}
            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadPreview}
                            isLoading={previewLoading} isDisabled={previewLoading}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            page={page}
                            perPage={perPage}
                            onSetPage={(_e, p) => setPage(p)}
                            onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
                            isCompact
                        />
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>

            {/* Results table */}
            {previewLoading && results.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>Evaluating filter...</EmptyStateBody>
                </EmptyState>
            ) : results.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No events in the stream.</EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Filter Preview" variant="compact">
                    <Thead>
                        <Tr>
                            <Th style={{ width: "50px" }}>Match</Th>
                            <Th>Time</Th>
                            <Th>Source</Th>
                            <Th>Connection</Th>
                            <Th>Event Type</Th>
                            <Th>Ref</Th>
                            <Th>Actor</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {results.map((result) => (
                            <Tr key={result.event.id}
                                isClickable
                                onRowClick={() => setSelectedEvent(result.event)}
                                style={{
                                    backgroundColor: result.matched ? matchBg : undefined,
                                    opacity: result.matched ? 1 : 0.45,
                                }}>
                                <Td>
                                    {result.matched
                                        ? <CheckCircleIcon color="var(--pf-v6-global--success-color--200, #3e8635)" />
                                        : <TimesCircleIcon color="var(--pf-v6-global--danger-color--100, #c9190b)" />}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(result.event.timestamp).toLocaleString()}
                                </Td>
                                <Td>
                                    <Label isCompact
                                        color={SOURCE_COLORS[result.event.source] || "grey"}>
                                        {result.event.source}
                                    </Label>
                                </Td>
                                <Td>{result.event.connectionId}</Td>
                                <Td>
                                    <Label isCompact>{result.event.type}</Label>
                                </Td>
                                <Td title={result.event.ref}>
                                    {formatRef(result.event.ref)}
                                </Td>
                                <Td>
                                    {(result.event.actor?.login as string)
                                        || (result.event.actor?.displayName as string)
                                        || "—"}
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            <StreamEventDetailModal
                event={selectedEvent}
                onClose={() => setSelectedEvent(null)}
                onSelectPath={(path) => {
                    // Insert the path at the end of the current expression,
                    // adding a space separator if the expression is non-empty
                    const separator = filterExpression && !filterExpression.endsWith(" ") ? " " : "";
                    setFilterExpression(filterExpression + separator + path);
                }}
            />

            {/* Help modal */}
            <FilterHelpModal isOpen={helpOpen} onClose={() => setHelpOpen(false)} />
        </div>
    );
}

// ── Filter Help Modal ───────────────────────────────────────────

function FilterHelpModal({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large">
            <ModalHeader title="Filter Expression Reference" />
            <ModalBody>
                <Title headingLevel="h4" size="md" style={{ marginBottom: "12px" }}>
                    Available Fields
                </Title>
                <Table aria-label="Available fields" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Field</Th>
                            <Th>Description</Th>
                            <Th>Example Values</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        <Tr><Td><code>event.type</code></Td><Td>Normalized event type</Td><Td><code>"issue.created"</code>, <code>"pr.merged"</code>, <code>"push"</code></Td></Tr>
                        <Tr><Td><code>event.source</code></Td><Td>Source system</Td><Td><code>"github"</code>, <code>"jira"</code></Td></Tr>
                        <Tr><Td><code>event.connectionId</code></Td><Td>Connection slug</Td><Td><code>"github-com"</code>, <code>"jira-prod"</code></Td></Tr>
                        <Tr><Td><code>event.ref</code></Td><Td>Full URL of the event subject</Td><Td><code>"https://github.com/owner/repo/issues/123"</code></Td></Tr>
                        <Tr><Td><code>event.timestamp</code></Td><Td>When the event occurred</Td><Td>ISO-8601 string</Td></Tr>
                        <Tr><Td><code>event.actor.login</code></Td><Td>Actor username or account ID</Td><Td><code>"octocat"</code></Td></Tr>
                        <Tr><Td><code>event.actor.displayName</code></Td><Td>Actor display name</Td><Td><code>"Octo Cat"</code></Td></Tr>
                        <Tr><Td><code>event.payload.issue.title</code></Td><Td>Issue title (issue events)</Td><Td><code>"Fix login bug"</code></Td></Tr>
                        <Tr><Td><code>event.payload.issue.state</code></Td><Td>Issue state (issue events)</Td><Td><code>"open"</code>, <code>"closed"</code></Td></Tr>
                        <Tr><Td><code>event.payload.issue.number</code></Td><Td>Issue number or key</Td><Td><code>"123"</code>, <code>"PROJ-456"</code></Td></Tr>
                        <Tr><Td><code>event.payload.pullRequest.baseBranch</code></Td><Td>PR target branch (PR events)</Td><Td><code>"main"</code></Td></Tr>
                        <Tr><Td><code>event.payload.pullRequest.isDraft</code></Td><Td>Whether the PR is a draft</Td><Td><code>true</code>, <code>false</code></Td></Tr>
                        <Tr><Td><code>event.payload.label.name</code></Td><Td>Label name (labeled/unlabeled events)</Td><Td><code>"bug"</code></Td></Tr>
                        <Tr><Td><code>event.payload.review.state</code></Td><Td>Review state (review events)</Td><Td><code>"approved"</code>, <code>"changes_requested"</code></Td></Tr>
                    </Tbody>
                </Table>

                <Title headingLevel="h4" size="md" style={{ marginTop: "24px", marginBottom: "12px" }}>
                    Operators
                </Title>
                <Table aria-label="Operators" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Operator</Th>
                            <Th>Description</Th>
                            <Th>Example</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        <Tr><Td><code>==</code></Td><Td>Equals</Td><Td><code>event.type == 'issue.created'</code></Td></Tr>
                        <Tr><Td><code>!=</code></Td><Td>Not equals</Td><Td><code>event.connectionId != 'jira-staging'</code></Td></Tr>
                        <Tr><Td><code>&&</code></Td><Td>Logical AND</Td><Td><code>event.source == 'github' && event.type == 'push'</code></Td></Tr>
                        <Tr><Td><code>||</code></Td><Td>Logical OR</Td><Td><code>event.type == 'issue.created' || event.type == 'issue.closed'</code></Td></Tr>
                        <Tr><Td><code>!</code></Td><Td>Logical NOT</Td><Td><code>!(event.type.startsWith('pr.'))</code></Td></Tr>
                        <Tr><Td><code>.startsWith('x')</code></Td><Td>String prefix match</Td><Td><code>event.type.startsWith('issue.')</code></Td></Tr>
                        <Tr><Td><code>.contains('x')</code></Td><Td>String substring match</Td><Td><code>event.ref.contains('my-repo')</code></Td></Tr>
                        <Tr><Td><code>.endsWith('x')</code></Td><Td>String suffix match</Td><Td><code>event.ref.endsWith('/pull/1')</code></Td></Tr>
                    </Tbody>
                </Table>

                <Title headingLevel="h4" size="md" style={{ marginTop: "24px", marginBottom: "12px" }}>
                    Examples
                </Title>
                <Table aria-label="Examples" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Expression</Th>
                            <Th>Description</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {[
                            ["event.type == 'issue.created'", "Match a specific event type"],
                            ["event.type.startsWith('pr.')", "Match all pull request events"],
                            ["event.connectionId == 'github-com' && event.type.startsWith('issue.')", "Issues from a specific connection"],
                            ["event.type == 'issue.created' || event.type == 'issue.closed'", "Match either of two event types"],
                            ["event.connectionId != 'jira-staging'", "Exclude events from a specific connection"],
                            ["event.payload.issue.state == 'open'", "Match events where the issue is open"],
                            ["event.payload.pullRequest.baseBranch == 'main'", "PR events targeting the main branch"],
                            ["event.type == 'pr.review.submitted' && event.payload.review.state == 'approved'", "Only approved PR reviews"],
                        ].map(([expr, desc]) => (
                            <Tr key={expr}>
                                <Td>
                                    <ClipboardCopy isReadOnly hoverTip="Copy" clickTip="Copied"
                                        variant={ClipboardCopyVariant.inlineCompact}>
                                        {expr}
                                    </ClipboardCopy>
                                </Td>
                                <Td>{desc}</Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            </ModalBody>
        </Modal>
    );
}

// ── Routing Tab ────────────────────────────────────────────────────

const ROUTING_TYPES: { value: RoutingRule["type"]; label: string }[] = [
    { value: "manager", label: "Send to Manager" },
    { value: "workflow-dispatch", label: "Dispatch to Workflows" },
    { value: "create-workflow", label: "Create Workflow" },
    { value: "invoke-action", label: "Invoke Action" },
];

function RoutingTab({ routing, setRouting }: {
    routing: RoutingRule[];
    setRouting: (v: RoutingRule[]) => void;
}) {
    const [workflowDefs, setWorkflowDefs] = useState<WorkflowDefinition[]>([]);
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);

    useEffect(() => {
        fetchWorkflowDefinitions(1, 100).then(r => setWorkflowDefs(r.items)).catch(console.error);
        fetchActionTypes(1, 100).then(r => setActionTypes(r.items)).catch(console.error);
    }, []);

    const addRule = () => {
        setRouting([...routing, { type: "manager" }]);
    };

    const removeRule = (index: number) => {
        setRouting(routing.filter((_, i) => i !== index));
    };

    const updateRule = (index: number, updates: Partial<RoutingRule>) => {
        const updated = [...routing];
        const rule = { ...updated[index], ...updates };
        // Clear irrelevant fields when type changes
        if (updates.type) {
            if (updates.type !== "create-workflow") delete rule.workflowDefinitionId;
            if (updates.type !== "invoke-action") delete rule.actionTypeId;
        }
        updated[index] = rule;
        setRouting(updated);
    };

    return (
        <div style={{ maxWidth: "800px" }}>
            <HelperText style={{ marginBottom: "16px" }}>
                <HelperTextItem>
                    Configure where matched events are routed. Add one or more routing rules to specify destinations.
                </HelperTextItem>
            </HelperText>

            <Button variant="link" icon={<PlusCircleIcon />} onClick={addRule}
                style={{ marginBottom: "16px" }}>
                Add Routing Rule
            </Button>

            {routing.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        No routing rules configured. Matched events will not be sent anywhere until at least one routing rule is added.
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Routing Rules" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Destination Type</Th>
                            <Th>Configuration</Th>
                            <Th style={{ width: "60px" }} />
                        </Tr>
                    </Thead>
                    <Tbody>
                        {routing.map((rule, index) => (
                            <Tr key={index}>
                                <Td style={{ verticalAlign: "middle" }}>
                                    <ObjectSelect
                                        value={ROUTING_TYPES.find(t => t.value === rule.type) || ROUTING_TYPES[0]}
                                        items={ROUTING_TYPES}
                                        onSelect={(item) =>
                                            updateRule(index, { type: item.value })
                                        }
                                        itemToString={(item) => item.label}
                                        testId={`routing-type-${index}`}
                                    />
                                </Td>
                                <Td style={{ verticalAlign: "middle" }}>
                                    {rule.type === "create-workflow" && (
                                        <ObjectSelect
                                            value={workflowDefs.find(wd => wd.id === rule.workflowDefinitionId) || null}
                                            items={workflowDefs}
                                            onSelect={(item) =>
                                                updateRule(index, { workflowDefinitionId: item?.id })
                                            }
                                            itemToString={(item) => item?.name || ""}
                                            noSelectionLabel="Select a workflow definition..."
                                            testId={`routing-workflow-${index}`}
                                        />
                                    )}
                                    {rule.type === "invoke-action" && (
                                        <ObjectSelect
                                            value={actionTypes.find(at => at.id === rule.actionTypeId) || null}
                                            items={actionTypes}
                                            onSelect={(item) =>
                                                updateRule(index, { actionTypeId: item?.id })
                                            }
                                            itemToString={(item) => item?.name || ""}
                                            noSelectionLabel="Select an action type..."
                                            testId={`routing-action-${index}`}
                                        />
                                    )}
                                    {(rule.type === "manager" || rule.type === "workflow-dispatch") && (
                                        <HelperText>
                                            <HelperTextItem variant="indeterminate">
                                                No additional configuration needed.
                                            </HelperTextItem>
                                        </HelperText>
                                    )}
                                </Td>
                                <Td style={{ verticalAlign: "middle", textAlign: "center" }}>
                                    <Button variant="plain" aria-label="Remove routing rule"
                                        onClick={() => removeRule(index)}>
                                        <TrashIcon />
                                    </Button>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </div>
    );
}

// ── Info Tab ─────────────────────────────────────────────────────

function InfoTab({ name, setName, description, setDescription, enabled, setEnabled, labels, setLabels, processEventsFrom, setProcessEventsFrom }: {
    name: string;
    setName: (v: string) => void;
    description: string;
    setDescription: (v: string) => void;
    enabled: boolean;
    setEnabled: (v: boolean) => void;
    labels: string[];
    setLabels: (v: string[]) => void;
    processEventsFrom: string;
    setProcessEventsFrom: (v: string) => void;
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
            <FormGroup label="Process Events From" fieldId="processEventsFrom">
                <TextInput id="processEventsFrom" type="datetime-local"
                    value={processEventsFrom}
                    onChange={(_e, v) => setProcessEventsFrom(v)} />
                <HelperText>
                    <HelperTextItem>
                        Events before this timestamp are ignored. Set to an earlier date to retroactively process historical events.
                    </HelperTextItem>
                </HelperText>
            </FormGroup>
            <FormGroup label="Labels" fieldId="labels">
                <LabelInput labels={labels} onChange={setLabels} />
            </FormGroup>
        </Form>
    );
}
