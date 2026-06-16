import { useState } from "react";
import {
    Button,
    ExpandableSection,
    Flex,
    FlexItem,
    Label,
} from "@patternfly/react-core";
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
    onPermissionRespond?: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>) => void;
}

export function AssistantToolUseBlock({
    toolName, input, result, isError,
    permissionId, permissionResolved, onPermissionRespond,
}: AssistantToolUseBlockProps) {
    const [isExpanded, setIsExpanded] = useState(false);

    const needsPermission = permissionId && !permissionResolved;
    const isAskUser = toolName === "AskUserQuestion";
    const borderColor = needsPermission
        ? (isAskUser ? "#2b9af3" : "#f0ab00")
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

            {permissionId && !isAskUser && (
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
                            <Flex>
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
                            </Flex>
                        </>
                    ) : (
                        <span style={{ color: "#6a6e73", fontStyle: "italic" }}>
                            Permission granted
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
