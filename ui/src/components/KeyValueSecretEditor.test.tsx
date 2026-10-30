import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { KeyValueSecretEditor, parseSecretValue } from "./KeyValueSecretEditor";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchSecrets: vi.fn().mockResolvedValue([{ id: 1, name: "GH_TOKEN" }, { id: 2, name: "API_KEY" }]),
}));

describe("parseSecretValue", () => {
    it("splits a prefix and a secret reference", () => {
        expect(parseSecretValue("Bearer ${secret:GH_TOKEN}")).toEqual({ prefix: "Bearer ", secret: "GH_TOKEN" });
        expect(parseSecretValue("${secret:X}")).toEqual({ prefix: "", secret: "X" });
        expect(parseSecretValue("plain")).toBeNull();
    });
});

describe("KeyValueSecretEditor", () => {
    it("shows secret references as secrets and masks plain values", async () => {
        render(<KeyValueSecretEditor label="Headers" keyPlaceholder="Header"
            values={{ Authorization: "Bearer ${secret:GH_TOKEN}", "X-Mode": "fast" }} onChange={() => {}} />);
        const auth = screen.getByTestId("kv-row-Authorization");
        expect(within(auth).getByLabelText("Prefix for Authorization")).toHaveValue("Bearer ");
        expect(await within(auth).findByLabelText("Secret for Authorization")).toHaveValue("GH_TOKEN");
        expect(screen.getByLabelText("Value for X-Mode")).toHaveAttribute("type", "password");
    });

    it("adds an entry whose value is a secret", async () => {
        const onChange = vi.fn();
        render(<KeyValueSecretEditor label="Environment" keyPlaceholder="Name" values={{}} onChange={onChange} />);
        await userEvent.type(screen.getByLabelText("New Environment name"), "GITHUB_TOKEN");
        await userEvent.click(screen.getByRole("radio", { name: "Secret" }));
        await userEvent.selectOptions(await screen.findByLabelText("New Environment secret"), "GH_TOKEN");
        await userEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onChange).toHaveBeenCalledWith({ GITHUB_TOKEN: "${secret:GH_TOKEN}" });
    });

    it("adds and removes plain entries", async () => {
        const onChange = vi.fn();
        const { rerender } = render(<KeyValueSecretEditor label="Environment" keyPlaceholder="Name" values={{}}
            onChange={onChange} />);
        await userEvent.type(screen.getByLabelText("New Environment name"), "MODE");
        await userEvent.type(screen.getByLabelText("New Environment value"), "fast");
        await userEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onChange).toHaveBeenLastCalledWith({ MODE: "fast" });
        rerender(<KeyValueSecretEditor label="Environment" keyPlaceholder="Name" values={{ MODE: "fast" }}
            onChange={onChange} />);
        await userEvent.click(screen.getByRole("button", { name: "Remove MODE" }));
        expect(onChange).toHaveBeenLastCalledWith({});
    });
});
