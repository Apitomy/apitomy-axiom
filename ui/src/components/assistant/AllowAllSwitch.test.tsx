import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as api from "../../config/api";
import { AllowAllSwitch } from "./AllowAllSwitch";

describe("AllowAllSwitch", () => {
    beforeEach(() => vi.restoreAllMocks());

    const setup = (allowAll: boolean) => {
        const onChange = vi.fn();
        const onError = vi.fn();
        const set = vi.spyOn(api, "setAllowAll").mockResolvedValue(undefined as never);
        render(<AllowAllSwitch sessionId="s1" allowAll={allowAll} onChange={onChange} onError={onError} />);
        return { onChange, onError, set };
    };

    it("asks for confirmation before enabling", async () => {
        const { onChange, set } = setup(false);
        fireEvent.click(screen.getByLabelText("Allow All"));
        expect(set).not.toHaveBeenCalled();
        fireEvent.click(await screen.findByRole("button", { name: "Enable" }));
        await waitFor(() => expect(set).toHaveBeenCalledWith("s1", true));
        expect(onChange).toHaveBeenCalledWith(true);
    });

    it("stays off when the confirmation is cancelled", async () => {
        const { onChange, set } = setup(false);
        fireEvent.click(screen.getByLabelText("Allow All"));
        fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));
        expect(set).not.toHaveBeenCalled();
        expect(onChange).not.toHaveBeenCalled();
    });

    it("disables without asking", async () => {
        const { onChange, set } = setup(true);
        fireEvent.click(screen.getByLabelText("Allow All"));
        await waitFor(() => expect(set).toHaveBeenCalledWith("s1", false));
        expect(onChange).toHaveBeenCalledWith(false);
        expect(screen.queryByRole("button", { name: "Enable" })).toBeNull();
    });

    it("reports a failure and leaves the state unchanged", async () => {
        const onChange = vi.fn();
        const onError = vi.fn();
        vi.spyOn(api, "setAllowAll").mockRejectedValue(new Error("boom"));
        render(<AllowAllSwitch sessionId="s1" allowAll={true} onChange={onChange} onError={onError} />);
        fireEvent.click(screen.getByLabelText("Allow All"));
        await waitFor(() => expect(onError).toHaveBeenCalledWith("boom"));
        expect(onChange).not.toHaveBeenCalled();
    });
});
