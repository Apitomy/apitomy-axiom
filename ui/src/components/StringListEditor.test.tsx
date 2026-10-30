import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { StringListEditor } from "./StringListEditor";

describe("StringListEditor", () => {
    it("adds, edits, moves and removes items", async () => {
        const onChange = vi.fn();
        const { rerender } = render(<StringListEditor label="Arguments" items={["-y"]} onChange={onChange} />);
        await userEvent.type(screen.getByLabelText("New Arguments item"), "pkg{Enter}");
        expect(onChange).toHaveBeenLastCalledWith(["-y", "pkg"]);
        rerender(<StringListEditor label="Arguments" items={["-y", "pkg"]} onChange={onChange} />);
        await userEvent.click(screen.getByRole("button", { name: "Move pkg up" }));
        expect(onChange).toHaveBeenLastCalledWith(["pkg", "-y"]);
        await userEvent.click(screen.getByRole("button", { name: "Remove -y" }));
        expect(onChange).toHaveBeenLastCalledWith(["pkg"]);
    });
});
