import { Label, type LabelProps } from "@patternfly/react-core";

const LABEL_COLORS: LabelProps["color"][] = [
    "blue", "teal", "green", "orange", "purple",
    "red", "orangered", "grey", "yellow",
];

/**
 * Deterministically maps a label string to a PatternFly label color
 * so that the same text always produces the same color.
 */
export function labelColor(text: string): LabelProps["color"] {
    let hash = 0;
    for (let i = 0; i < text.length; i++) {
        hash = (hash * 31 + text.charCodeAt(i)) | 0;
    }
    return LABEL_COLORS[Math.abs(hash) % LABEL_COLORS.length];
}

/**
 * A PatternFly Label whose color is automatically determined by
 * its text content. Accepts all standard Label props except `color`.
 */
export function ColoredLabel({ children, ...rest }: Omit<LabelProps, "color">) {
    const text = typeof children === "string" ? children : String(children);
    return <Label color={labelColor(text)} {...rest}>{children}</Label>;
}
