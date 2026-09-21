import { useState } from "react";
import { Tooltip } from "@patternfly/react-core";

interface JsonPathTreeProps {
    /** The JSON data to render. */
    data: unknown;
    /** Path prefix (e.g., "event") prepended to all constructed paths. */
    pathPrefix?: string;
    /**
     * Called when the user clicks a field key or value. Receives the text
     * to insert into the expression:
     * - Key click: the dot-notation path (e.g., "event.actor.login")
     * - Value click: an equality expression (e.g., "event.actor.login == 'octocat'")
     * If not provided, nothing is clickable.
     */
    onSelectPath?: (expression: string) => void;
}

/**
 * Renders a JSON value as an interactive, syntax-colored tree inside a
 * styled panel. Object keys and primitive values are clickable when
 * {@link onSelectPath} is provided.
 */
export function JsonPathTree({ data, pathPrefix = "event", onSelectPath }: JsonPathTreeProps) {
    return (
        <div style={{
            backgroundColor: "var(--pf-v6-global--BackgroundColor--200, #f0f0f0)",
            borderRadius: "6px",
            border: "1px solid var(--pf-v6-global--BorderColor--100, #d2d2d2)",
            overflow: "auto",
            maxHeight: "600px",
        }}>
            <pre style={{
                fontFamily: "var(--pf-v6-global--FontFamily--monospace, monospace)",
                fontSize: "13px",
                lineHeight: "1.6",
                margin: 0,
                padding: "16px",
            }}>
                <JsonValue value={data} path={pathPrefix} indent={0}
                    onSelectPath={onSelectPath} />
            </pre>
        </div>
    );
}

// ── Internal components ─────────────────────────────────────────

const INDENT_SIZE = 2;

const hoverStyle = {
    cursor: "pointer",
    borderRadius: "2px",
    padding: "0 2px",
    margin: "0 -2px",
    backgroundColor: "var(--pf-v6-global--BackgroundColor--300, rgba(0,0,0,0.06))",
};

function JsonValue({ value, path, indent, onSelectPath }: {
    value: unknown;
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
}) {
    if (value === null || value === undefined) {
        return (
            <ClickableValue
                display={<span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>null</span>}
                path={path}
                literal="null"
                onSelectPath={onSelectPath}
            />
        );
    }
    if (typeof value === "string") {
        return (
            <ClickableValue
                display={<span style={{ color: "var(--pf-v6-global--success-color--200, #3e8635)" }}>"{value}"</span>}
                path={path}
                literal={`'${value}'`}
                onSelectPath={onSelectPath}
            />
        );
    }
    if (typeof value === "number") {
        return (
            <ClickableValue
                display={<span style={{ color: "var(--pf-v6-global--palette--purple-500, #6753ac)" }}>{value}</span>}
                path={path}
                literal={String(value)}
                onSelectPath={onSelectPath}
            />
        );
    }
    if (typeof value === "boolean") {
        return (
            <ClickableValue
                display={<span style={{ color: "var(--pf-v6-global--danger-color--200, #a30000)" }}>{value ? "true" : "false"}</span>}
                path={path}
                literal={value ? "true" : "false"}
                onSelectPath={onSelectPath}
            />
        );
    }
    if (Array.isArray(value)) {
        return <JsonArray items={value} path={path} indent={indent}
            onSelectPath={onSelectPath} />;
    }
    if (typeof value === "object") {
        return <JsonObject obj={value as Record<string, unknown>} path={path}
            indent={indent} onSelectPath={onSelectPath} />;
    }
    return <span>{String(value)}</span>;
}

function JsonObject({ obj, path, indent, onSelectPath }: {
    obj: Record<string, unknown>;
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
}) {
    const entries = Object.entries(obj);
    if (entries.length === 0) {
        return <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>{"{}"}</span>;
    }

    const innerIndent = indent + INDENT_SIZE;
    const pad = " ".repeat(innerIndent);
    const closePad = " ".repeat(indent);

    return (
        <>
            <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>{"{"}</span>
            {"\n"}
            {entries.map(([key, val], i) => {
                const childPath = `${path}.${key}`;
                const isLast = i === entries.length - 1;
                return (
                    <span key={key}>
                        {pad}
                        <ClickableKey
                            keyName={key}
                            path={childPath}
                            onSelectPath={onSelectPath}
                        />
                        <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>: </span>
                        <JsonValue value={val} path={childPath} indent={innerIndent}
                            onSelectPath={onSelectPath} />
                        {!isLast && <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>,</span>}
                        {"\n"}
                    </span>
                );
            })}
            {closePad}<span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>{"}"}</span>
        </>
    );
}

function JsonArray({ items, path, indent, onSelectPath }: {
    items: unknown[];
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
}) {
    if (items.length === 0) {
        return <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>[]</span>;
    }

    const innerIndent = indent + INDENT_SIZE;
    const pad = " ".repeat(innerIndent);
    const closePad = " ".repeat(indent);

    return (
        <>
            <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>[</span>
            {"\n"}
            {items.map((item, i) => {
                const isLast = i === items.length - 1;
                return (
                    <span key={i}>
                        {pad}
                        <JsonValue value={item} path={`${path}[${i}]`}
                            indent={innerIndent} onSelectPath={onSelectPath} />
                        {!isLast && <span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>,</span>}
                        {"\n"}
                    </span>
                );
            })}
            {closePad}<span style={{ color: "var(--pf-v6-global--Color--200, #6a6e73)" }}>]</span>
        </>
    );
}

function ClickableKey({ keyName, path, onSelectPath }: {
    keyName: string;
    path: string;
    onSelectPath?: (expression: string) => void;
}) {
    const [hovered, setHovered] = useState(false);

    const baseStyle = { color: "var(--pf-v6-global--info-color--200, #0066cc)" };

    if (!onSelectPath) {
        return <span style={baseStyle}>"{keyName}"</span>;
    }

    const style = hovered
        ? { ...baseStyle, ...hoverStyle, borderBottom: "1px solid var(--pf-v6-global--info-color--200, #0066cc)" }
        : { ...baseStyle, cursor: "pointer" };

    return (
        <Tooltip content={`Insert path: ${path}`} position="top">
            <span
                style={style}
                onMouseEnter={() => setHovered(true)}
                onMouseLeave={() => setHovered(false)}
                onClick={(e) => {
                    e.stopPropagation();
                    onSelectPath(path);
                }}
            >
                "{keyName}"
            </span>
        </Tooltip>
    );
}

function ClickableValue({ display, path, literal, onSelectPath }: {
    display: React.ReactNode;
    path: string;
    literal: string;
    onSelectPath?: (expression: string) => void;
}) {
    const [hovered, setHovered] = useState(false);

    if (!onSelectPath) {
        return <>{display}</>;
    }

    const expression = `${path} == ${literal}`;

    const style = hovered ? { ...hoverStyle } : { cursor: "pointer" };

    return (
        <Tooltip content={`Insert: ${expression}`} position="top">
            <span
                style={style}
                onMouseEnter={() => setHovered(true)}
                onMouseLeave={() => setHovered(false)}
                onClick={(e) => {
                    e.stopPropagation();
                    onSelectPath(expression);
                }}
            >
                {display}
            </span>
        </Tooltip>
    );
}
