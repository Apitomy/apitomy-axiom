import { useState, useMemo } from "react";
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
    /** Whether to use dark mode colors. */
    isDarkTheme?: boolean;
}

// ── Theme-aware color palettes ──────────────────────────────────

interface ColorPalette {
    background: string;
    border: string;
    key: string;
    keyHoverBg: string;
    keyHoverBorder: string;
    string: string;
    number: string;
    boolean: string;
    null: string;
    punctuation: string;
    hoverBg: string;
}

const LIGHT_PALETTE: ColorPalette = {
    background: "#f0f0f0",
    border: "#d2d2d2",
    key: "#0066cc",
    keyHoverBg: "rgba(0, 102, 204, 0.08)",
    keyHoverBorder: "#0066cc",
    string: "#3e8635",
    number: "#6753ac",
    boolean: "#a30000",
    null: "#6a6e73",
    punctuation: "#6a6e73",
    hoverBg: "rgba(0, 0, 0, 0.06)",
};

const DARK_PALETTE: ColorPalette = {
    background: "#1e1e1e",
    border: "#3c3c3c",
    key: "#6cb2f7",
    keyHoverBg: "rgba(108, 178, 247, 0.12)",
    keyHoverBorder: "#6cb2f7",
    string: "#73c991",
    number: "#b5a2d6",
    boolean: "#f28b82",
    null: "#8a8d90",
    punctuation: "#8a8d90",
    hoverBg: "rgba(255, 255, 255, 0.06)",
};

/**
 * Renders a JSON value as an interactive, syntax-colored tree inside a
 * styled panel. Object keys and primitive values are clickable when
 * {@link onSelectPath} is provided. Supports light and dark themes.
 */
export function JsonPathTree({ data, pathPrefix = "event", onSelectPath, isDarkTheme }: JsonPathTreeProps) {
    const palette = isDarkTheme ? DARK_PALETTE : LIGHT_PALETTE;

    return (
        <div style={{
            backgroundColor: palette.background,
            borderRadius: "6px",
            border: `1px solid ${palette.border}`,
            overflow: "auto",
            maxHeight: "600px",
        }}>
            <pre style={{
                fontFamily: "var(--pf-v6-global--FontFamily--monospace, monospace)",
                fontSize: "13px",
                lineHeight: "1.6",
                margin: 0,
                padding: "16px",
                color: isDarkTheme ? "#d4d4d4" : "#1b1d21",
            }}>
                <JsonValue value={data} path={pathPrefix} indent={0}
                    onSelectPath={onSelectPath} palette={palette} />
            </pre>
        </div>
    );
}

// ── Internal components ─────────────────────────────────────────

const INDENT_SIZE = 2;

function JsonValue({ value, path, indent, onSelectPath, palette }: {
    value: unknown;
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
    palette: ColorPalette;
}) {
    if (value === null || value === undefined) {
        return (
            <ClickableValue
                display={<span style={{ color: palette.null }}>null</span>}
                path={path}
                literal="null"
                onSelectPath={onSelectPath}
                palette={palette}
            />
        );
    }
    if (typeof value === "string") {
        return (
            <ClickableValue
                display={<span style={{ color: palette.string }}>"{value}"</span>}
                path={path}
                literal={`'${value}'`}
                onSelectPath={onSelectPath}
                palette={palette}
            />
        );
    }
    if (typeof value === "number") {
        return (
            <ClickableValue
                display={<span style={{ color: palette.number }}>{value}</span>}
                path={path}
                literal={String(value)}
                onSelectPath={onSelectPath}
                palette={palette}
            />
        );
    }
    if (typeof value === "boolean") {
        return (
            <ClickableValue
                display={<span style={{ color: palette.boolean }}>{value ? "true" : "false"}</span>}
                path={path}
                literal={value ? "true" : "false"}
                onSelectPath={onSelectPath}
                palette={palette}
            />
        );
    }
    if (Array.isArray(value)) {
        return <JsonArray items={value} path={path} indent={indent}
            onSelectPath={onSelectPath} palette={palette} />;
    }
    if (typeof value === "object") {
        return <JsonObject obj={value as Record<string, unknown>} path={path}
            indent={indent} onSelectPath={onSelectPath} palette={palette} />;
    }
    return <span>{String(value)}</span>;
}

function JsonObject({ obj, path, indent, onSelectPath, palette }: {
    obj: Record<string, unknown>;
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
    palette: ColorPalette;
}) {
    const entries = Object.entries(obj);
    if (entries.length === 0) {
        return <span style={{ color: palette.punctuation }}>{"{}"}</span>;
    }

    const innerIndent = indent + INDENT_SIZE;
    const pad = " ".repeat(innerIndent);
    const closePad = " ".repeat(indent);

    return (
        <>
            <span style={{ color: palette.punctuation }}>{"{"}</span>
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
                            palette={palette}
                        />
                        <span style={{ color: palette.punctuation }}>: </span>
                        <JsonValue value={val} path={childPath} indent={innerIndent}
                            onSelectPath={onSelectPath} palette={palette} />
                        {!isLast && <span style={{ color: palette.punctuation }}>,</span>}
                        {"\n"}
                    </span>
                );
            })}
            {closePad}<span style={{ color: palette.punctuation }}>{"}"}</span>
        </>
    );
}

function JsonArray({ items, path, indent, onSelectPath, palette }: {
    items: unknown[];
    path: string;
    indent: number;
    onSelectPath?: (expression: string) => void;
    palette: ColorPalette;
}) {
    if (items.length === 0) {
        return <span style={{ color: palette.punctuation }}>[]</span>;
    }

    const innerIndent = indent + INDENT_SIZE;
    const pad = " ".repeat(innerIndent);
    const closePad = " ".repeat(indent);

    return (
        <>
            <span style={{ color: palette.punctuation }}>[</span>
            {"\n"}
            {items.map((item, i) => {
                const isLast = i === items.length - 1;
                return (
                    <span key={i}>
                        {pad}
                        <JsonValue value={item} path={`${path}[${i}]`}
                            indent={innerIndent} onSelectPath={onSelectPath} palette={palette} />
                        {!isLast && <span style={{ color: palette.punctuation }}>,</span>}
                        {"\n"}
                    </span>
                );
            })}
            {closePad}<span style={{ color: palette.punctuation }}>]</span>
        </>
    );
}

function ClickableKey({ keyName, path, onSelectPath, palette }: {
    keyName: string;
    path: string;
    onSelectPath?: (expression: string) => void;
    palette: ColorPalette;
}) {
    const [hovered, setHovered] = useState(false);

    const baseStyle = useMemo(() => ({ color: palette.key }), [palette.key]);

    if (!onSelectPath) {
        return <span style={baseStyle}>"{keyName}"</span>;
    }

    const style = hovered
        ? {
            ...baseStyle,
            cursor: "pointer",
            backgroundColor: palette.keyHoverBg,
            borderBottom: `1px solid ${palette.keyHoverBorder}`,
            borderRadius: "2px",
            padding: "0 2px",
            margin: "0 -2px",
        }
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

function ClickableValue({ display, path, literal, onSelectPath, palette }: {
    display: React.ReactNode;
    path: string;
    literal: string;
    onSelectPath?: (expression: string) => void;
    palette: ColorPalette;
}) {
    const [hovered, setHovered] = useState(false);

    if (!onSelectPath) {
        return <>{display}</>;
    }

    const expression = `${path} == ${literal}`;

    const style = hovered
        ? {
            cursor: "pointer",
            backgroundColor: palette.hoverBg,
            borderRadius: "2px",
            padding: "0 2px",
            margin: "0 -2px",
        }
        : { cursor: "pointer" as const };

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
