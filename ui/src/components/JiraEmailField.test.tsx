import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { JiraEmailField, isValidJiraEmail } from "./JiraEmailField";

const SECRETS = ["JIRA_EMAIL", "JIRA_API_TOKEN"];

/** Holds the value in state so typing works like in the forms. */
function Harness({ initial, onChange }: { initial: string; onChange: (v: string) => void }) {
    const [value, setValue] = useState(initial);
    return <JiraEmailField value={value} secrets={SECRETS}
        onChange={(v) => { setValue(v); onChange(v); }} />;
}

describe("isValidJiraEmail", () => {
    it("accepts empty, addresses and secret references", () => {
        expect(isValidJiraEmail("")).toBe(true);
        expect(isValidJiraEmail("user@example.com")).toBe(true);
        expect(isValidJiraEmail("${secret:JIRA_EMAIL}")).toBe(true);
        expect(isValidJiraEmail("not-an-email")).toBe(false);
    });
});

describe("JiraEmailField", () => {
    it("edits a plain email address", async () => {
        const onChange = vi.fn();
        render(<Harness initial="" onChange={onChange} />);
        expect(screen.getByRole("radio", { name: "Plain text" })).toBeChecked();
        await userEvent.type(screen.getByLabelText("Jira email address"), "me@example.com");
        expect(onChange).toHaveBeenLastCalledWith("me@example.com");
    });

    it("chooses an existing secret without typing the reference", async () => {
        const onChange = vi.fn();
        render(<Harness initial="" onChange={onChange} />);
        await userEvent.click(screen.getByRole("radio", { name: "Secret" }));
        await userEvent.selectOptions(screen.getByLabelText("Jira email secret"), "JIRA_EMAIL");
        expect(onChange).toHaveBeenLastCalledWith("${secret:JIRA_EMAIL}");
    });

    it("shows a stored secret reference as the selected secret", () => {
        render(<JiraEmailField value="${secret:JIRA_EMAIL}" secrets={SECRETS} onChange={() => {}} />);
        expect(screen.getByRole("radio", { name: "Secret" })).toBeChecked();
        expect(screen.getByLabelText("Jira email secret")).toHaveValue("JIRA_EMAIL");
    });

    it("marks a missing secret", () => {
        render(<JiraEmailField value="${secret:GONE}" secrets={SECRETS} onChange={() => {}} />);
        expect(screen.getByRole("option", { name: "GONE (does not exist)" })).toBeInTheDocument();
    });

    it("clears the value when switching modes", async () => {
        const onChange = vi.fn();
        render(<Harness initial="me@example.com" onChange={onChange} />);
        await userEvent.click(screen.getByRole("radio", { name: "Secret" }));
        expect(onChange).toHaveBeenLastCalledWith("");
    });

    it("shows an error for an invalid address", () => {
        render(<JiraEmailField value="nope" secrets={SECRETS} onChange={() => {}} />);
        expect(screen.getByText(/valid email address/)).toBeInTheDocument();
    });
});
