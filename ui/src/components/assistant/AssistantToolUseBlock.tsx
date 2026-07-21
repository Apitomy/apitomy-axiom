import { useState } from "react";
import {
    Button,
    Content,
    ExpandableSection,
    Flex,
    FlexItem,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    TextInput,
} from "@patternfly/react-core";
import SearchPlusIcon from "@patternfly/react-icons/dist/esm/icons/search-plus-icon";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Light as SyntaxHighlighter } from "react-syntax-highlighter";
import json from "react-syntax-highlighter/dist/esm/languages/hljs/json";
import bash from "react-syntax-highlighter/dist/esm/languages/hljs/bash";
import { stackoverflowLight } from "react-syntax-highlighter/dist/esm/styles/hljs";
import { AssistantAskUserQuestion } from "./AssistantAskUserQuestion";
import "./AssistantToolUseBlock.css";

SyntaxHighlighter.registerLanguage("json", json);
SyntaxHighlighter.registerLanguage("bash", bash);

type LabelColor = "blue" | "green" | "purple" | "teal" | "orange" | "grey" | "red";

function getToolColor(toolName: string): LabelColor {
    if (toolName === "AskUserQuestion") return "teal";
    if (toolName === "EnterPlanMode" || toolName === "Agent") return "orange";
    if (toolName.startsWith("mcp__axiom-sdk__") || toolName.startsWith("mcp__axiom__")) return "green";
    if (toolName.startsWith("mcp__axiom-tools__")) return "purple";
    if (toolName.startsWith("mcp__")) return "grey";
    return "blue";
}

interface AssistantToolUseBlockProps {
    toolName: string;
    input?: Record<string, unknown>;
    result?: string;
    isError?: boolean;
    permissionId?: string;
    permissionResolved?: boolean;
    permissionAllowed?: boolean;
    onPermissionRespond?: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
    onCreateAutoApproval?: (toolName: string, fieldName: string | undefined,
        pattern: string | undefined, permissionId: string) => void;
}

export function AssistantToolUseBlock({
    toolName, input, result, isError,
    permissionId, permissionResolved, permissionAllowed, onPermissionRespond,
    onCreateAutoApproval,
}: AssistantToolUseBlockProps) {
    const [isExpanded, setIsExpanded] = useState(false);
    const [showPatternUI, setShowPatternUI] = useState(false);
    const [customPattern, setCustomPattern] = useState("");
    const [isPlanModalOpen, setIsPlanModalOpen] = useState(false);

    const needsPermission = permissionId && !permissionResolved;
    const isAskUser = toolName === "AskUserQuestion";
    const isPlanApproval = toolName === "ExitPlanMode";
    const borderVariant = needsPermission
        ? (isPlanApproval ? "plan" : isAskUser ? "ask" : "permission")
        : undefined;

    const inputPreview = input
        ? JSON.stringify(input).substring(0, 100)
        : "";

    const contextSummary = getContextSummary(toolName, input);

    return (
        <div className="axiom-tool-use" data-border={borderVariant || undefined}>
            <div className="axiom-tool-use__header">
                <ExpandableSection
                    toggleContent={
                        <span>
                            <Label isCompact color={isError ? "red" : getToolColor(toolName)}>
                                {toolName}
                            </Label>
                            {inputPreview && (
                                <span className="axiom-tool-use__input-preview">
                                    {inputPreview}{input && JSON.stringify(input).length > 100 ? "..." : ""}
                                </span>
                            )}
                        </span>
                    }
                    isExpanded={isExpanded}
                    onToggle={(_e, expanded) => setIsExpanded(expanded)}
                    isIndented
                >
                    {input && (
                        <div>
                            <div className="axiom-tool-use__section-label">Input</div>
                            <div className="axiom-tool-use__code">
                                <SyntaxHighlighter
                                    language="json"
                                    style={stackoverflowLight}
                                    wrapLongLines
                                >
                                    {JSON.stringify(input, null, 2)}
                                </SyntaxHighlighter>
                            </div>
                        </div>
                    )}
                    {result && (
                        <div className={input ? "axiom-tool-use__result-divider" : undefined}>
                            <div className={`axiom-tool-use__section-label${isError ? " axiom-tool-use__section-label--error" : ""}`}>
                                {isError ? "Error" : "Result"}
                            </div>
                            <div className={`axiom-tool-use__code${isError ? " axiom-tool-use__code--error" : ""}`}>
                                <SyntaxHighlighter
                                    language={isJson(result) ? "json" : "bash"}
                                    style={stackoverflowLight}
                                    wrapLongLines
                                >
                                    {isJson(result) ? formatJson(result) : result}
                                </SyntaxHighlighter>
                            </div>
                        </div>
                    )}
                </ExpandableSection>
            </div>

            {permissionId && isAskUser && Array.isArray(input?.questions) && (
                <div className="axiom-tool-use__ask-section">
                    <AssistantAskUserQuestion
                        permissionId={permissionId}
                        questions={input.questions as {
                            question: string;
                            header?: string;
                            options: { label: string; description?: string }[];
                            multiSelect?: boolean;
                        }[]}
                        onRespond={onPermissionRespond!}
                        resolved={permissionResolved}
                    />
                </div>
            )}

            {permissionId && isPlanApproval && (
                <div className="axiom-tool-use__plan-section"
                    data-resolved={!needsPermission ? "true" : undefined}>
                    {needsPermission ? (
                        <>
                            <Flex alignItems={{ default: "alignItemsCenter" }}
                                className="axiom-tool-use__plan-review-header">
                                <FlexItem>
                                    <span className="axiom-tool-use__plan-review-title">
                                        Plan ready for review
                                    </span>
                                </FlexItem>
                                {!!input?.plan && (
                                    <FlexItem>
                                        <Button variant="plain" size="sm"
                                            aria-label="View plan in full screen"
                                            onClick={() => setIsPlanModalOpen(true)}
                                            className="axiom-tool-use__view-plan-btn">
                                            <SearchPlusIcon />
                                        </Button>
                                    </FlexItem>
                                )}
                            </Flex>
                            {!!input?.plan && (
                                <div className="assistant-markdown axiom-tool-use__plan-content">
                                    <Content>
                                        <Markdown remarkPlugins={[remarkGfm]}>
                                            {input.plan as string}
                                        </Markdown>
                                    </Content>
                                </div>
                            )}
                            <Flex>
                                <FlexItem>
                                    <Button variant="primary" size="sm"
                                        className="axiom-tool-use__plan-approve-btn"
                                        onClick={() => onPermissionRespond?.(permissionId, true, input)}>
                                        Approve Plan
                                    </Button>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="secondary" size="sm"
                                        onClick={() => onPermissionRespond?.(permissionId, false, input)}>
                                        Reject
                                    </Button>
                                </FlexItem>
                            </Flex>
                        </>
                    ) : (
                        <Flex alignItems={{ default: "alignItemsCenter" }}>
                            <FlexItem>
                                <span className={permissionAllowed
                                    ? "axiom-tool-use__plan-status--approved"
                                    : "axiom-tool-use__plan-status--rejected"}>
                                    {permissionAllowed ? "Plan approved" : "Plan rejected"}
                                </span>
                            </FlexItem>
                            {!!input?.plan && (
                                <FlexItem>
                                    <Button variant="plain" size="sm"
                                        aria-label="View plan"
                                        onClick={() => setIsPlanModalOpen(true)}
                                        className="axiom-tool-use__view-plan-btn">
                                        <SearchPlusIcon />
                                    </Button>
                                </FlexItem>
                            )}
                        </Flex>
                    )}
                    {!!input?.plan && (
                        <Modal
                            isOpen={isPlanModalOpen}
                            onClose={() => setIsPlanModalOpen(false)}
                            variant="large"
                            aria-label="Plan details"
                        >
                            <ModalHeader title="Plan" />
                            <ModalBody>
                                <Content>
                                    <div className="assistant-markdown">
                                        <Markdown remarkPlugins={[remarkGfm]}>
                                            {input.plan as string}
                                        </Markdown>
                                    </div>
                                </Content>
                            </ModalBody>
                        </Modal>
                    )}
                </div>
            )}

            {permissionId && !isAskUser && !isPlanApproval && (
                <div className="axiom-tool-use__permission-section"
                    data-resolved={!needsPermission ? "true" : undefined}>
                    {needsPermission ? (
                        <>
                            <div className="axiom-tool-use__permission-title">
                                Permission required
                            </div>
                            {contextSummary && (
                                <div className="axiom-tool-use__context-summary">
                                    {contextSummary}
                                </div>
                            )}
                            <Flex style={{ marginBottom: showPatternUI ? 8 : 0 }}>
                                <FlexItem>
                                    <Button variant="primary" size="sm"
                                        onClick={() => onPermissionRespond?.(permissionId, true, input)}>
                                        Allow
                                    </Button>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="secondary" size="sm"
                                        onClick={() => onPermissionRespond?.(permissionId, false, input)}>
                                        Deny
                                    </Button>
                                </FlexItem>
                                {onCreateAutoApproval && (
                                    <FlexItem>
                                        <Button variant="link" size="sm"
                                            onClick={() => setShowPatternUI(!showPatternUI)}>
                                            {showPatternUI ? "Cancel" : "Allow Pattern..."}
                                        </Button>
                                    </FlexItem>
                                )}
                            </Flex>
                            {showPatternUI && onCreateAutoApproval && (
                                <div className="axiom-tool-use__pattern-ui">
                                    <div className="axiom-tool-use__pattern-hint">
                                        Auto-approve future {toolName} calls matching a pattern:
                                    </div>
                                    {getSuggestedPatterns(toolName, input).map((suggestion) => (
                                        <Button key={suggestion.label} variant="tertiary" size="sm"
                                            className="axiom-tool-use__pattern-suggestion"
                                            onClick={() => {
                                                onCreateAutoApproval(
                                                    toolName, suggestion.fieldName,
                                                    suggestion.pattern, permissionId);
                                                setShowPatternUI(false);
                                            }}>
                                            {suggestion.label}
                                        </Button>
                                    ))}
                                    <Flex className="axiom-tool-use__pattern-custom-row">
                                        <FlexItem grow={{ default: "grow" }}>
                                            <TextInput
                                                value={customPattern}
                                                onChange={(_e, v) => setCustomPattern(v)}
                                                placeholder="Custom regex..."
                                                aria-label="Custom auto-approval pattern"
                                                size={30}
                                            />
                                        </FlexItem>
                                        <FlexItem>
                                            <Button variant="primary" size="sm"
                                                isDisabled={!customPattern.trim()}
                                                onClick={() => {
                                                    const info = getFieldInfo(toolName);
                                                    onCreateAutoApproval(
                                                        toolName, info.fieldName,
                                                        customPattern.trim(), permissionId);
                                                    setShowPatternUI(false);
                                                    setCustomPattern("");
                                                }}>
                                                Apply Rule
                                            </Button>
                                        </FlexItem>
                                    </Flex>
                                </div>
                            )}
                        </>
                    ) : (
                        <span className={permissionAllowed
                            ? "axiom-tool-use__permission-status--granted"
                            : "axiom-tool-use__permission-status--denied"}>
                            {permissionAllowed ? "Permission granted" : "Permission denied"}
                        </span>
                    )}
                </div>
            )}
        </div>
    );
}

function getContextSummary(toolName: string, input?: Record<string, unknown>): string | null {
    if (!input) return null;
    switch (toolName) {
        case "Bash":
            return input.command as string || null;
        case "Write":
            return `Write to: ${input.file_path || "unknown file"}`;
        case "Edit":
            return `Edit: ${input.file_path || "unknown file"}`;
        case "Read":
            return `Read: ${input.file_path || "unknown file"}`;
        case "Agent":
            return `Agent: ${input.description || input.prompt || ""}`.substring(0, 200);
        default:
            if (typeof input === "object" && Object.keys(input).length > 0) {
                return JSON.stringify(input, null, 2).substring(0, 200);
            }
            return null;
    }
}

function isJson(text: string): boolean {
    const trimmed = text.trim();
    return (trimmed.startsWith("{") || trimmed.startsWith("[")) && tryParseJson(trimmed) !== null;
}

function tryParseJson(text: string): unknown {
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}

function formatJson(text: string): string {
    try {
        return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
        return text;
    }
}

interface PatternSuggestion {
    label: string;
    fieldName: string | undefined;
    pattern: string | undefined;
}

function getFieldInfo(toolName: string): { fieldName: string | undefined } {
    switch (toolName) {
        case "Bash": return { fieldName: "command" };
        case "Read": case "Write": case "Edit": return { fieldName: "file_path" };
        default: return { fieldName: undefined };
    }
}

function getSuggestedPatterns(toolName: string, input?: Record<string, unknown>): PatternSuggestion[] {
    const suggestions: PatternSuggestion[] = [];

    if (toolName === "Bash" && input?.command) {
        const command = input.command as string;
        const firstWord = command.split(/\s+/)[0];
        if (firstWord) {
            suggestions.push({
                label: `${firstWord} *`,
                fieldName: "command",
                pattern: `^${escapeRegex(firstWord)} `,
            });
        }
    } else if ((toolName === "Read" || toolName === "Write" || toolName === "Edit")
            && input?.file_path) {
        const filePath = input.file_path as string;
        const dir = filePath.substring(0, filePath.lastIndexOf("/"));
        suggestions.push({
            label: `Allow all ${toolName}`,
            fieldName: undefined,
            pattern: undefined,
        });
        if (dir) {
            suggestions.push({
                label: `${dir}/...`,
                fieldName: "file_path",
                pattern: `^${escapeRegex(dir)}/`,
            });
        }
    } else {
        suggestions.push({
            label: `Allow all ${toolName}`,
            fieldName: undefined,
            pattern: undefined,
        });
    }

    return suggestions;
}

function escapeRegex(text: string): string {
    return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
