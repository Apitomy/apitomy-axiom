import {
    Alert,
    Button,
    Flex,
    FlexItem,
    Tooltip,
} from "@patternfly/react-core";

interface AssistantPermissionPromptProps {
    permissionId: string;
    toolName: string;
    input?: Record<string, unknown>;
    onRespond: (permissionId: string, allow: boolean, toolInput?: Record<string, unknown>,
        always?: boolean) => void;
    resolved?: boolean;
    /** Patterns approved for the rest of the session by "Always Allow"; button hidden when empty. */
    alwaysPatterns?: string[];
}

export function AssistantPermissionPrompt({
    permissionId,
    toolName,
    input,
    onRespond,
    resolved,
    alwaysPatterns,
}: AssistantPermissionPromptProps) {
    const hasAlways = (alwaysPatterns?.length ?? 0) > 0;
    const inputPreview = input
        ? JSON.stringify(input, null, 2).substring(0, 300)
        : "";

    return (
        <Alert
            variant="warning"
            isInline
            title={`Permission requested: ${toolName}`}
            style={{ margin: "8px 0" }}
        >
            {inputPreview && (
                <pre style={{
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-all",
                    fontSize: "12px",
                    maxHeight: "150px",
                    overflow: "auto",
                    margin: "8px 0",
                }}>
                    {inputPreview}
                </pre>
            )}
            <Flex>
                <FlexItem>
                    <Button
                        variant="primary"
                        size="sm"
                        onClick={() => onRespond(permissionId, true, input)}
                        isDisabled={resolved}
                    >
                        Allow
                    </Button>
                </FlexItem>
                {hasAlways && (
                    <FlexItem>
                        <Tooltip content={`Allow for the rest of this session: ${alwaysPatterns!.join(", ")}`}>
                            <Button
                                variant="secondary"
                                size="sm"
                                onClick={() => onRespond(permissionId, true, input, true)}
                                isDisabled={resolved}
                            >
                                Always Allow
                            </Button>
                        </Tooltip>
                    </FlexItem>
                )}
                <FlexItem>
                    <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => onRespond(permissionId, false, input)}
                        isDisabled={resolved}
                    >
                        Deny
                    </Button>
                </FlexItem>
            </Flex>
        </Alert>
    );
}
