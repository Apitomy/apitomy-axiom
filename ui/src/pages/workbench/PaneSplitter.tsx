import { useCallback, useEffect, useRef } from "react";

/** Props for {@link PaneSplitter}. */
export interface PaneSplitterProps {
    /** Current width of the pane being resized. */
    width: number;
    /** +1 when dragging right grows the pane (left pane), -1 when it shrinks it (right pane). */
    direction: 1 | -1;
    /** Called while dragging with the new width. */
    onResize: (width: number) => void;
    /** Called once when dragging ends with the final width. */
    onResizeEnd: (width: number) => void;
    /** Accessible label. */
    label: string;
    /** Extra class names (e.g. the grid-placement modifier). */
    className?: string;
}

const MIN: number = 160;
const MAX: number = 800;

/**
 * Draggable vertical splitter between two workbench panes.
 */
export function PaneSplitter({ width, direction, onResize, onResizeEnd, label, className }: PaneSplitterProps) {
    const draggingRef = useRef<boolean>(false);
    const startXRef = useRef<number>(0);
    const startWidthRef = useRef<number>(width);
    const lastRef = useRef<number>(width);

    const onMouseMove = useCallback((e: MouseEvent) => {
        if (!draggingRef.current) {
            return;
        }
        const next: number = Math.min(MAX, Math.max(MIN,
            startWidthRef.current + direction * (e.clientX - startXRef.current)));
        lastRef.current = next;
        onResize(next);
    }, [direction, onResize]);

    const onMouseUp = useCallback(() => {
        if (!draggingRef.current) {
            return;
        }
        draggingRef.current = false;
        document.body.style.cursor = "";
        document.body.style.userSelect = "";
        onResizeEnd(lastRef.current);
    }, [onResizeEnd]);

    useEffect(() => {
        document.addEventListener("mousemove", onMouseMove);
        document.addEventListener("mouseup", onMouseUp);
        return () => {
            document.removeEventListener("mousemove", onMouseMove);
            document.removeEventListener("mouseup", onMouseUp);
        };
    }, [onMouseMove, onMouseUp]);

    const handleMouseDown = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        draggingRef.current = true;
        startXRef.current = e.clientX;
        startWidthRef.current = width;
        lastRef.current = width;
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";
    }, [width]);

    return (
        <div className={className ? `axiom-workbench__splitter ${className}` : "axiom-workbench__splitter"} role="separator" aria-orientation="vertical"
            aria-label={label} onMouseDown={handleMouseDown} />
    );
}
