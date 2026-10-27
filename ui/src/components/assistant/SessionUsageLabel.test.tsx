import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { SessionUsageLabel, sessionUsageTooltip } from "./SessionUsageLabel";

describe("SessionUsageLabel", () => {
    it("shows the cost when there is one", () => {
        render(<SessionUsageLabel costUsd={0.12345} inputTokens={1000} outputTokens={200} />);
        expect(screen.getByText("$0.1235")).toBeTruthy();
    });

    it("shows the total tokens when there is no cost", () => {
        render(<SessionUsageLabel costUsd={0} inputTokens={1000} outputTokens={234} />);
        expect(screen.getByText(`${(1234).toLocaleString()} tokens`)).toBeTruthy();
    });

    it("renders nothing without usage", () => {
        const { container } = render(<SessionUsageLabel costUsd={0} inputTokens={0} outputTokens={0} />);
        expect(container.textContent).toBe("");
    });

    it("describes the in/out tokens for the tooltip", () => {
        expect(sessionUsageTooltip(10, 20)).toBe("Tokens in: 10 / out: 20");
    });
});
