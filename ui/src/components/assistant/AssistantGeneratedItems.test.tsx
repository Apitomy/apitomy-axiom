import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { AssistantGeneratedItems } from "./AssistantGeneratedItems";
import { fetchAssistantItemContent, fetchAssistantItems } from "../../config/api";

vi.mock("../../config/api", () => ({
    fetchAssistantItems: vi.fn(),
    fetchAssistantItemContent: vi.fn(),
}));

const viewerProps: { workflow?: { nodes: unknown[]; edges: unknown[] } }[] = [];
vi.mock("@apitomy/flow-ui", () => ({
    WorkflowViewer: (props: { workflow: { nodes: unknown[]; edges: unknown[] } }) => {
        viewerProps.push(props);
        return <div data-testid="workflow-viewer">{props.workflow.nodes.length} nodes</div>;
    },
}));

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) => <pre data-testid="code-editor">{code}</pre>,
    Language: { json: "json" },
}));

const WORKFLOW_CONTENT = {
    id: "triage",
    name: "triage",
    nodes: [
        { id: "s", type: "start", name: "Start", config: {}, position: { x: 0, y: 0 } },
        { id: "e", type: "end", name: "End", config: {}, position: { x: 200, y: 0 } },
    ],
    edges: [{ id: "e1", source: "s", target: "e", priority: 0, isDefault: true }],
};

const ITEMS = [
    { type: "event-sources", name: "github-com", valid: true },
    { type: "event-sources", name: "jira-prod", valid: true },
    { type: "workflows", name: "triage", valid: true },
    { type: "workflows", name: "triage-string", valid: true },
    { type: "workflows", name: "triage-inputs", valid: true },
    { type: "subscriptions", name: "new-issues", valid: true },
];

const CONTENT: Record<string, Record<string, unknown>> = {
    "event-sources/github-com": {
        id: "github-com", name: "GitHub", sourceType: "github", baseUrl: "https://api.github.com",
        configuration: { repositories: ["my-org/my-repo", "my-org/other"], extra: 5 },
    },
    "event-sources/jira-prod": {
        id: "jira-prod", name: "Jira", sourceType: "jira", baseUrl: "https://x.atlassian.net",
        configuration: { projects: ["PROJ"] },
    },
    "workflows/triage": { name: "triage", description: "Triage issues", content: WORKFLOW_CONTENT },
    "workflows/triage-string": { name: "triage-string", content: JSON.stringify(WORKFLOW_CONTENT) },
    "workflows/triage-inputs": {
        name: "triage-inputs",
        content: {
            ...WORKFLOW_CONTENT,
            nodes: [
                {
                    id: "s", type: "start", name: "Start", position: { x: 0, y: 0 },
                    config: {
                        inputs: [
                            { name: "ref", type: "string", required: true, description: "Issue URL" },
                            { name: "priority", type: "string" },
                        ],
                    },
                },
                WORKFLOW_CONTENT.nodes[1],
            ],
        },
    },
    "subscriptions/new-issues": {
        name: "new-issues", enabled: false,
        filterExpression: "event.type == 'issue.created'",
        routing: [{ type: "create-workflow", workflowDefinitionName: "triage" }],
    },
};

async function openItem(name: string) {
    render(<AssistantGeneratedItems sessionId="s1" refreshTrigger={0} />);
    await userEvent.click(await screen.findByText(name));
    return screen.findByRole("dialog");
}

describe("AssistantGeneratedItems previews", () => {
    beforeEach(() => {
        viewerProps.length = 0;
        vi.mocked(fetchAssistantItems).mockResolvedValue(ITEMS as never);
        vi.mocked(fetchAssistantItemContent).mockImplementation(
            async (_s: string, type: string, name: string) => CONTENT[`${type}/${name}`] as never);
    });

    it("labels workflow and subscription items", async () => {
        render(<AssistantGeneratedItems sessionId="s1" refreshTrigger={0} />);
        await screen.findByText("triage");
        expect(screen.getAllByText("Workflow")).toHaveLength(3);
        expect(screen.getByText("Subscription")).toBeInTheDocument();
    });

    it("opens the event source preview", async () => {
        const dialog = await openItem("github-com");
        expect(within(dialog).getByText("Event Source: github-com")).toBeInTheDocument();
    });

    it("shows GitHub repositories as a list, plus unrecognized settings", async () => {
        const dialog = await openItem("github-com");
        const list = within(dialog).getByRole("list", { name: "Repositories" });
        const items = within(list).getAllByRole("listitem");
        expect(items.map((i) => i.textContent)).toEqual(["my-org/my-repo", "my-org/other"]);
        expect(within(list.closest("dl") as HTMLElement).queryByText(/"repositories"/)).not.toBeInTheDocument();
        expect(within(dialog).getByText("extra")).toBeInTheDocument();
    });

    it("shows Jira projects as a list", async () => {
        const dialog = await openItem("jira-prod");
        const list = within(dialog).getByRole("list", { name: "Projects" });
        expect(within(list).getByText("PROJ")).toBeInTheDocument();
    });

    it("opens the workflow preview with a diagram", async () => {
        const dialog = await openItem("triage");
        expect(within(dialog).getByText("Workflow: triage")).toBeInTheDocument();
        expect(within(dialog).getByTestId("workflow-viewer")).toHaveTextContent("2 nodes");
        expect(viewerProps.at(-1)?.workflow?.edges).toHaveLength(1);
    });

    it("parses workflow content stored as a JSON string", async () => {
        const dialog = await openItem("triage-string");
        expect(within(dialog).getByTestId("workflow-viewer")).toHaveTextContent("2 nodes");
    });

    it("lists start inputs on the details tab", async () => {
        const dialog = await openItem("triage-inputs");
        await userEvent.click(within(dialog).getByRole("tab", { name: "Details" }));
        const list = within(dialog).getByRole("list", { name: "Start inputs" });
        const items = within(list).getAllByRole("listitem");
        expect(items).toHaveLength(2);
        expect(items[0]).toHaveTextContent("ref");
        expect(items[0]).toHaveTextContent("string");
        expect(items[0]).toHaveTextContent("required");
        expect(items[0]).toHaveTextContent("Issue URL");
        expect(items[1]).toHaveTextContent("priority");
        expect(items[1]).not.toHaveTextContent("required");
    });

    it("opens the subscription preview with filter and routing", async () => {
        const dialog = await openItem("new-issues");
        expect(within(dialog).getByText("Subscription: new-issues")).toBeInTheDocument();
        expect(within(dialog).getByText("event.type == 'issue.created'")).toBeInTheDocument();
        await userEvent.click(within(dialog).getByRole("tab", { name: "Routing" }));
        expect(within(dialog).getByText("create-workflow")).toBeInTheDocument();
        expect(within(dialog).getByText("triage")).toBeInTheDocument();
    });
});
