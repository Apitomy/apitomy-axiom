import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { InboxInputsTable, isExternalUrl } from "./InboxInputsTable";

describe("isExternalUrl", () => {
    it("accepts whole http and https URLs", () => {
        expect(isExternalUrl("https://example.com/a?b=c")).toBe(true);
        expect(isExternalUrl("  http://example.com  ")).toBe(true);
    });

    it("rejects other schemes, embedded URLs, and plain text", () => {
        expect(isExternalUrl("javascript:alert(1)")).toBe(false);
        expect(isExternalUrl("ftp://example.com")).toBe(false);
        expect(isExternalUrl("see https://example.com")).toBe(false);
        expect(isExternalUrl("https://exa mple.com")).toBe(false);
        expect(isExternalUrl("hello")).toBe(false);
        expect(isExternalUrl("")).toBe(false);
    });
});

describe("InboxInputsTable", () => {
    it("renders a header-less striped table of inputs", () => {
        render(<InboxInputsTable details={[
            { label: "orderId", value: "42" },
            { label: "notes", value: "" },
        ]} />);
        const table = screen.getByRole("grid", { name: "Task inputs" });
        expect(table.className).toContain("pf-m-striped");
        expect(table.querySelector("thead")).toBeNull();
        expect(table.querySelectorAll("tbody tr")).toHaveLength(2);
        expect(table).toHaveTextContent("orderId");
        expect(table).toHaveTextContent("42");
        expect(table).toHaveTextContent("\u2014");
    });

    it("links URL values externally and leaves other values as text", () => {
        render(<InboxInputsTable details={[
            { label: "pr", value: "https://github.com/Apitomy/apitomy-axiom/pull/1" },
            { label: "bad", value: "javascript:alert(1)" },
        ]} />);
        const link = screen.getByRole("link", { name: /pull\/1/ });
        expect(link).toHaveAttribute("href", "https://github.com/Apitomy/apitomy-axiom/pull/1");
        expect(link).toHaveAttribute("target", "_blank");
        expect(link).toHaveAttribute("rel", "noopener noreferrer");
        expect(screen.getAllByRole("link")).toHaveLength(1);
        expect(screen.getByText("javascript:alert(1)")).toBeInTheDocument();
    });
});
