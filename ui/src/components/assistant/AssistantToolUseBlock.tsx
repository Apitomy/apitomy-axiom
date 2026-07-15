import { useState } from "react";
import {
    Button,
    Content,
    ExpandableSection,
    Flex,
    FlexItem,
    Label,
    TextInput,
} from "@patternfly/react-core";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Light as SyntaxHighlighter } from "react-syntax-highlighter";
import json from "react-syntax-highlighter/dist/esm/languages/hljs/json";
import bash from "react-syntax-highlighter/dist/esm/languages/hljs/bash";
import { stackoverflowLight } from "react-syntax-highlighter/dist/esm/styles/hljs";
import { AssistantAskUserQuestion } from "./AssistantAskUserQuestion";

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

    const needsPermission = permissionId && !permissionResolved;
    const isAskUser = toolName === "AskUserQuestion";
    const isPlanApproval = toolName === "ExitPlanMode";
    const borderColor = needsPermission
        ? (isPlanApproval ? "#3e8635" : isAskUser ? "#2b9af3" : "#f0ab00")
        : undefined;

    const inputPreview = input
        ? JSON.stringify(input).substring(0, 100)
        : "";

    const contextSummary = getContextSummary(toolName, input);

    const codeStyle = {
        margin: 0,
        borderRadius: "4px",
        fontSize: "12px",
        maxHeight: "250px",
        overflow: "auto",
    };

    return (
        <div style={{
            margin: "4px 0",
            borderRadius: "6px",
            overflow: "hidden",
            border: borderColor ? `2px solid ${borderColor}` : undefined,
        }}>
            <div style={{
                padding: "8px 12px",
                backgroundColor: "#f0f0f0",
                fontSize: "13px",
            }}>
                <ExpandableSection
                    toggleContent={
                        <span>
                            <Label isCompact color={isError ? "red" : getToolColor(toolName)}>
                                {toolName}
                            </Label>
                            {inputPreview && (
                                <span style={{ marginLeft: 8, color: "#6a6e73", fontSize: "12px" }}>
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
                        <div style={{ marginBottom: result ? 8 : 0 }}>
                            <SyntaxHighlighter
                                language="json"
                                style={stackoverflowLight}
                                customStyle={codeStyle}
                                wrapLongLines
                            >
                                {JSON.stringify(input, null, 2)}
                            </SyntaxHighlighter>
                        </div>
                    )}
                    {result && (
                        <SyntaxHighlighter
                            language={isJson(result) ? "json" : "bash"}
                            style={stackoverflowLight}
                            customStyle={{
                                ...codeStyle,
                                ...(isError ? { backgroundColor: "#fef3f2" } : {}),
                            }}
                            wrapLongLines
                        >
                            {isJson(result) ? formatJson(result) : result}
                        </SyntaxHighlighter>
                    )}
                </ExpandableSection>
            </div>

            {permissionId && isAskUser && Array.isArray(input?.questions) && (
                <div style={{ borderTop: "1px solid #d2d2d2" }}>
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
                <div style={{
                    padding: "10px 12px",
                    backgroundColor: needsPermission ? "#f3faf3" : "#f0f0f0",
                    borderTop: "1px solid #d2d2d2",
                    fontSize: "13px",
                }}>
                    {needsPermission ? (
                        <>
                            <div style={{ fontWeight: 600, marginBottom: 8 }}>
                                Plan ready for review
                            </div>
                            {input?.plan && (
                                <div className="assistant-markdown" style={{
                                    marginBottom: 10,
                                    padding: "12px 16px",
                                    backgroundColor: "white",
                                    borderRadius: "4px",
                                    border: "1px solid #c6e3c6",
                                    maxHeight: "300px",
                                    overflow: "auto",
                                    fontSize: "13px",
                                }}>
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
                                        style={{ backgroundColor: "#3e8635", borderColor: "#3e8635" }}
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
                        <span style={{ color: permissionAllowed ? "#3e8635" : "#c9190b", fontStyle: "italic" }}>
                            {permissionAllowed ? "Plan approved" : "Plan rejected"}
                        </span>
                    )}
                </div>
            )}

            {permissionId && !isAskUser && !isPlanApproval && (
                <div style={{
                    padding: "10px 12px",
                    backgroundColor: needsPermission ? "#fdf7e7" : "#f0f0f0",
                    borderTop: "1px solid #d2d2d2",
                    fontSize: "13px",
                }}>
                    {needsPermission ? (
                        <>
                            <div style={{ fontWeight: 600, marginBottom: 6 }}>
                                Permission required
                            </div>
                            {contextSummary && (
                                <div style={{
                                    marginBottom: 8,
                                    padding: "6px 10px",
                                    backgroundColor: "white",
                                    borderRadius: "4px",
                                    border: "1px solid #d2d2d2",
                                    fontFamily: "monospace",
                                    fontSize: "12px",
                                    whiteSpace: "pre-wrap",
                                    wordBreak: "break-all",
                                    maxHeight: "120px",
                                    overflow: "auto",
                                }}>
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
                                <div style={{
                                    padding: "8px 10px",
                                    backgroundColor: "white",
                                    borderRadius: "4px",
                                    border: "1px solid #d2d2d2",
                                }}>
                                    <div style={{ fontSize: "12px", color: "#6a6e73", marginBottom: 6 }}>
                                        Auto-approve future {toolName} calls matching a pattern:
                                    </div>
                                    {getSuggestedPatterns(toolName, input).map((suggestion) => (
                                        <Button key={suggestion.label} variant="tertiary" size="sm"
                                            style={{ marginRight: 6, marginBottom: 4 }}
                                            onClick={() => {
                                                onCreateAutoApproval(
                                                    toolName, suggestion.fieldName,
                                                    suggestion.pattern, permissionId);
                                                setShowPatternUI(false);
                                            }}>
                                            {suggestion.label}
                                        </Button>
                                    ))}
                                    <Flex style={{ marginTop: 6, alignItems: "center", gap: 6 }}>
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
                        <span style={{ color: permissionAllowed ? "#6a6e73" : "#c9190b", fontStyle: "italic" }}>
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
