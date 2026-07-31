import { useId, useState } from "react";
import {
    Button,
    ExpandableSection,
    Flex,
    FlexItem,
    Radio,
    Checkbox,
    TextInput,
    Label,
} from "@patternfly/react-core";

interface QuestionOption {
    label: string;
    description?: string;
}

interface Question {
    question: string;
    header?: string;
    options: QuestionOption[];
    multiSelect?: boolean;
}

interface AssistantAskUserQuestionProps {
    permissionId: string;
    questions: Question[];
    onRespond: (permissionId: string, allow: boolean, updatedInput?: Record<string, unknown>) => void;
    resolved?: boolean;
}

export function AssistantAskUserQuestion({
    permissionId,
    questions,
    onRespond,
    resolved,
}: AssistantAskUserQuestionProps) {
    const blockId = useId();
    const [answers, setAnswers] = useState<Record<number, string | string[]>>({});
    const [otherText, setOtherText] = useState<Record<number, string>>({});
    const [isExpanded, setIsExpanded] = useState(!resolved);

    const handleSingleSelect = (questionIdx: number, label: string) => {
        setAnswers((prev) => ({ ...prev, [questionIdx]: label }));
    };

    const handleMultiSelect = (questionIdx: number, label: string, checked: boolean) => {
        setAnswers((prev) => {
            const current = (prev[questionIdx] as string[]) || [];
            if (checked) {
                return { ...prev, [questionIdx]: [...current, label] };
            }
            return { ...prev, [questionIdx]: current.filter((l) => l !== label) };
        });
    };

    const handleSubmit = () => {
        const answersMap: Record<string, string> = {};
        questions.forEach((q, idx) => {
            const answer = answers[idx];
            if (answer === "Other" && otherText[idx]) {
                answersMap[q.question] = otherText[idx];
            } else if (Array.isArray(answer)) {
                const selected = answer.includes("Other") && otherText[idx]
                    ? [...answer.filter((a) => a !== "Other"), otherText[idx]]
                    : answer;
                answersMap[q.question] = selected.join(", ");
            } else if (answer) {
                answersMap[q.question] = answer;
            }
        });

        onRespond(permissionId, true, {
            questions: questions.map((q) => ({
                ...q,
                options: q.options.map((o) => ({ ...o })),
            })),
            answers: answersMap,
        });
        setIsExpanded(false);
    };

    const allAnswered = questions.every((_, idx) => {
        const a = answers[idx];
        if (!a) return false;
        if (Array.isArray(a)) return a.length > 0;
        return true;
    });

    const summaryText = resolved
        ? questions.map((q, idx) => {
            const a = answers[idx];
            const display = a === "Other" && otherText[idx]
                ? otherText[idx]
                : Array.isArray(a) ? a.join(", ") : (a || "—");
            return `${q.header || "Q"}: ${display}`;
        }).join(" | ")
        : undefined;

    return (
        <div style={{
            padding: "8px 12px",
            backgroundColor: "var(--axiom--color--info--bg)",
        }}>
            <ExpandableSection
                toggleContent={
                    <span>
                        <Label isCompact color="blue" style={{ marginRight: 8 }}>
                            Question
                        </Label>
                        {resolved && summaryText && (
                            <span className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                {summaryText}
                            </span>
                        )}
                        {!resolved && (
                            <span style={{ fontSize: "13px" }}>
                                Claude has a question
                            </span>
                        )}
                    </span>
                }
                isExpanded={isExpanded}
                onToggle={(_e, expanded) => setIsExpanded(expanded)}
                isIndented
            >
                {questions.map((q, qIdx) => (
                    <div key={qIdx} style={{ marginBottom: qIdx < questions.length - 1 ? 16 : 8 }}>
                        {q.header && (
                            <div className="axiom-text-subtle" style={{
                                fontSize: "11px",
                                fontWeight: 600,
                                textTransform: "uppercase",
                                marginBottom: 4,
                            }}>
                                {q.header}
                            </div>
                        )}
                        <div style={{ fontWeight: 600, marginBottom: 8 }}>
                            {q.question}
                        </div>
                        <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                            {q.options.concat(
                                q.options.some((o) => o.label === "Other") ? [] : [{ label: "Other", description: "Provide your own answer" }]
                            ).map((opt) => {
                                const isOther = opt.label === "Other";
                                if (q.multiSelect) {
                                    const selected = ((answers[qIdx] as string[]) || []).includes(opt.label);
                                    return (
                                        <div key={opt.label}>
                                            <Checkbox
                                                id={`${blockId}-q${qIdx}-${opt.label}`}
                                                label={
                                                    <span>
                                                        {opt.label}
                                                        {opt.description && (
                                                            <span className="axiom-text-subtle" style={{ marginLeft: 6, fontSize: "13px" }}>
                                                                — {opt.description}
                                                            </span>
                                                        )}
                                                    </span>
                                                }
                                                isChecked={selected}
                                                onChange={(_e, checked) => handleMultiSelect(qIdx, opt.label, checked)}
                                                isDisabled={resolved}
                                            />
                                            {isOther && selected && (
                                                <TextInput
                                                    value={otherText[qIdx] || ""}
                                                    onChange={(_e, val) => setOtherText((prev) => ({ ...prev, [qIdx]: val }))}
                                                    placeholder="Type your answer..."
                                                    style={{ marginTop: 4, marginLeft: 24 }}
                                                    isDisabled={resolved}
                                                />
                                            )}
                                        </div>
                                    );
                                }
                                const selected = answers[qIdx] === opt.label;
                                return (
                                    <div key={opt.label}>
                                        <Radio
                                            id={`${blockId}-q${qIdx}-${opt.label}`}
                                            name={`${blockId}-question-${qIdx}`}
                                            label={
                                                <span>
                                                    {opt.label}
                                                    {opt.description && (
                                                        <span className="axiom-text-subtle" style={{ marginLeft: 6, fontSize: "13px" }}>
                                                            — {opt.description}
                                                        </span>
                                                    )}
                                                </span>
                                            }
                                            isChecked={selected}
                                            onChange={() => handleSingleSelect(qIdx, opt.label)}
                                            isDisabled={resolved}
                                        />
                                        {isOther && selected && (
                                            <TextInput
                                                value={otherText[qIdx] || ""}
                                                onChange={(_e, val) => setOtherText((prev) => ({ ...prev, [qIdx]: val }))}
                                                placeholder="Type your answer..."
                                                style={{ marginTop: 4, marginLeft: 24 }}
                                                isDisabled={resolved}
                                            />
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                ))}
                {!resolved && (
                    <Flex style={{ marginTop: 12 }}>
                        <FlexItem>
                            <Button
                                variant="primary"
                                size="sm"
                                onClick={handleSubmit}
                                isDisabled={!allAnswered}
                            >
                                Submit
                            </Button>
                        </FlexItem>
                    </Flex>
                )}
            </ExpandableSection>
        </div>
    );
}
