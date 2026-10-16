import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as api from "../config/api";
import { EnvironmentTab } from "./EnvironmentTab";

beforeEach(() => {
    vi.spyOn(api, "fetchSecrets").mockResolvedValue([
        { id: 1, name: "GH_TOKEN" }, { id: 2, name: "JIRA_API_TOKEN" }] as never);
});

describe("EnvironmentTab", () => {
    it("says that secrets are not injected automatically", () => {
        render(<EnvironmentTab envVars={{}} onChange={vi.fn()} />);
        expect(screen.getByText(/Secrets are not injected automatically/)).toBeInTheDocument();
        expect(screen.getByText("No environment variables: the process gets no secrets.")).toBeInTheDocument();
    });

    it("adds a secret by name as NAME=${secret:NAME}", async () => {
        const onChange = vi.fn();
        render(<EnvironmentTab envVars={{ OTHER: "x" }} onChange={onChange} />);
        const picker = await screen.findByLabelText("Add secret");
        await waitFor(() => expect(picker.querySelectorAll("option").length).toBe(3));
        await userEvent.selectOptions(picker, "JIRA_API_TOKEN");
        expect(onChange).toHaveBeenCalledWith({ OTHER: "x", JIRA_API_TOKEN: "${secret:JIRA_API_TOKEN}" });
    });

    it("does not offer secrets that are already declared", async () => {
        render(<EnvironmentTab envVars={{ GH_TOKEN: "${secret:GH_TOKEN}" }} onChange={vi.fn()} />);
        const picker = await screen.findByLabelText("Add secret");
        await waitFor(() => expect(picker.querySelectorAll("option").length).toBe(2));
        expect([...picker.querySelectorAll("option")].map((o) => o.textContent)).toEqual(
            ["Add secret…", "JIRA_API_TOKEN"]);
    });
});
