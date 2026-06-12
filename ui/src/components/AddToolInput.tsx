import { useEffect, useState, useCallback } from "react";
import { Button, Flex, FlexItem, Tooltip } from "@patternfly/react-core";
import ListIcon from "@patternfly/react-icons/dist/esm/icons/list-icon";
import {
    fetchToolsets, type Toolset,
    fetchTools, type ToolDefinition,
    fetchMcpServers, type McpServer,
} from "../config/api";
import { TypeaheadAddInput, type TypeaheadAddSuggestion } from "./TypeaheadAddInput";
import { BrowseToolsModal } from "./BrowseToolsModal";

/**
 * Well-known Claude Code built-in tools available for autocomplete.
 */
const BUILTIN_TOOLS = [
    "Read",
    "Glob",
    "Grep",
    "Edit",
    "Write",
    "Bash(ls *)",
    "Bash(cat *)",
    "Bash(head *)",
    "Bash(tail *)",
    "Bash(find *)",
    "Bash(wc *)",
    "Bash(file *)",
    "Bash(git log *)",
    "Bash(git diff *)",
    "Bash(git show *)",
    "Bash(git status *)",
    "Bash(git branch *)",
    "Bash(git add *)",
    "Bash(git commit *)",
    "Bash(git checkout *)",
    "Bash(git switch *)",
    "Bash(git push *)",
    "Bash(git merge *)",
    "Bash(gh issue *)",
    "Bash(gh pr *)",
    "Bash(gh api *)",
    "Bash(gh repo *)",
    "Bash(date *)",
    "Bash(mkdir *)",
    "Bash(cp *)",
    "Bash(mv *)",
];

const AXIOM_SDK_TOOLS = [
    "mcp__axiom-sdk__axiom_fire_event",
    "mcp__axiom-sdk__axiom_list_projects",
    "mcp__axiom-sdk__axiom_get_project",
    "mcp__axiom-sdk__axiom_create_task",
    "mcp__axiom-sdk__axiom_get_task_status",
    "mcp__axiom-sdk__axiom_add_thread_entry",
    "mcp__axiom-sdk__axiom_close_project",
    "mcp__axiom-sdk__axiom_reopen_project",
    "mcp__axiom-sdk__axiom_add_project_label",
    "mcp__axiom-sdk__axiom_remove_project_label",
    "mcp__axiom-sdk__axiom_list_tools",
    "mcp__axiom-sdk__axiom_list_report_definitions",
];

const TOOLSET_STYLE: React.CSSProperties = {
    color: "var(--pf-t--global--color--brand--default)",
    fontWeight: 500,
};
const MCP_STYLE: React.CSSProperties = {
    fontFamily: "var(--pf-t--global--font--family--mono)",
};

interface AddToolInputProps {
    /** Called when a tool is selected (either from suggestions or freeform). */
    onAdd: (tool: string) => void;
    /** Called by the browse modal to replace the entire tool list. */
    onReplace?: (tools: string[]) => void;
    /** Tools already in the list, used to filter out duplicates from suggestions. */
    existingTools?: string[];
    /** Placeholder text for the input. */
    placeholder?: string;
}

/**
 * Typeahead input for adding tools to an allowed tools list.
 * Wraps TypeaheadAddInput with tool-specific suggestions loaded
 * from toolsets, custom tools, MCP servers, and built-in tools.
 */
export function AddToolInput({
    onAdd,
    onReplace,
    existingTools = [],
    placeholder = "Type a tool name or @ToolsetName and press Enter",
}: AddToolInputProps) {
    const [toolsets, setToolsets] = useState<Toolset[]>([]);
    const [customTools, setCustomTools] = useState<ToolDefinition[]>([]);
    const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
    const [isBrowseOpen, setIsBrowseOpen] = useState(false);

    useEffect(() => {
        Promise.all([fetchToolsets(), fetchTools(1, 1000), fetchMcpServers()])
            .then(([ts, toolsResult, servers]) => {
                setToolsets(ts);
                setCustomTools(toolsResult.items);
                setMcpServers(servers);
            })
            .catch(console.error);
    }, []);

    const suggestions: TypeaheadAddSuggestion[] = useCallback(() => {
        const items: TypeaheadAddSuggestion[] = [];

        toolsets.forEach((ts) =>
            items.push({ value: `@${ts.name}`, style: TOOLSET_STYLE }));

        customTools.forEach((t) =>
            items.push({ value: `mcp__axiom-tools__${t.name}`, style: MCP_STYLE }));

        AXIOM_SDK_TOOLS.forEach((t) =>
            items.push({ value: t, style: MCP_STYLE }));

        mcpServers.forEach((s) =>
            items.push({ value: `mcp__${s.name}__*`, style: MCP_STYLE }));

        BUILTIN_TOOLS.forEach((t) =>
            items.push({ value: t }));

        return items;
    }, [toolsets, customTools, mcpServers])();

    return (
        <>
            <Flex>
                <FlexItem grow={{ default: "grow" }}>
                    <TypeaheadAddInput
                        onAdd={onAdd}
                        suggestions={suggestions}
                        existingItems={existingTools}
                        placeholder={placeholder}
                    />
                </FlexItem>
                <FlexItem>
                    <Tooltip content="Manage allowed tools">
                        <Button variant="control" aria-label="Manage allowed tools"
                            onClick={() => setIsBrowseOpen(true)}>
                            <ListIcon />
                        </Button>
                    </Tooltip>
                </FlexItem>
            </Flex>
            <BrowseToolsModal
                isOpen={isBrowseOpen}
                onClose={() => setIsBrowseOpen(false)}
                onSave={onReplace || ((tools) => { tools.forEach(onAdd); })}
                existingTools={existingTools}
            />
        </>
    );
}
