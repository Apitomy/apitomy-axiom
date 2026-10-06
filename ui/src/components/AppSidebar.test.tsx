import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppSidebar } from "./AppSidebar";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchInboxCount: vi.fn().mockResolvedValue({ count: 0 }),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));

describe("AppSidebar", () => {
    it("lists Runs as the first navigation item", async () => {
        renderWithRouter(<AppSidebar />);
        await screen.findByText("Inbox");
        expect(screen.getAllByRole("listitem")[0]).toHaveTextContent("Runs");
    });

    it("renders the Inbox navigation item", async () => {
        renderWithRouter(<AppSidebar />);
        expect(await screen.findByText("Inbox")).toBeInTheDocument();
    });

    it("has no Manager navigation item", async () => {
        renderWithRouter(<AppSidebar />, "/engine");
        await screen.findByText("Inbox");
        expect(screen.queryByText(/manager/i)).not.toBeInTheDocument();
    });

    it("has no Reports or Report Definitions navigation items", async () => {
        renderWithRouter(<AppSidebar />, "/action-types");
        await screen.findByText("Inbox");
        expect(screen.queryByText("Reports")).not.toBeInTheDocument();
        expect(screen.queryByText("Report Definitions")).not.toBeInTheDocument();
    });

    it("has no Scheduled Jobs or Job Runs navigation items", async () => {
        renderWithRouter(<AppSidebar />, "/action-types");
        await screen.findByText("Inbox");
        expect(screen.queryByText("Scheduled Jobs")).not.toBeInTheDocument();
        expect(screen.queryByText("Job Runs")).not.toBeInTheDocument();
    });

    it("keeps only Runs, Subjects, Inbox and Dashboards as top-level items", async () => {
        const { container } = renderWithRouter(<AppSidebar />);
        await screen.findByText("Inbox");
        const rootList = container.querySelector(".pf-v6-c-nav__list") as HTMLElement;
        const topLevel = Array.from(rootList.querySelectorAll(":scope > li"))
            .filter((li) => li.querySelector("button[aria-expanded]") === null)
            .map((li) => li.textContent?.trim());
        expect(topLevel).toEqual(["Runs", "Subjects", "Inbox", "Dashboards"]);
    });

    it("groups event pages under an expanded Events section", async () => {
        renderWithRouter(<AppSidebar />, "/events/subscriptions");
        const toggle = await screen.findByRole("button", { name: "Events" });
        expect(toggle).toHaveAttribute("aria-expanded", "true");
        expect(screen.getByText("Event Sources")).toBeVisible();
        expect(screen.getByText("Subscriptions")).toBeVisible();
        expect(screen.getByText("Event Stream")).toBeVisible();
    });

    it("groups Workflows and Action Types under an expanded Components section", async () => {
        renderWithRouter(<AppSidebar />, "/components/workflows");
        const toggle = await screen.findByRole("button", { name: "Components" });
        expect(toggle).toHaveAttribute("aria-expanded", "true");
        expect(screen.getByText("Workflows")).toBeVisible();
        expect(screen.getByText("Action Types")).toBeVisible();
    });

    it("shows Subjects and not Projects", async () => {
        renderWithRouter(<AppSidebar />, "/subjects");
        await screen.findByText("Inbox");
        expect(screen.getByText("Subjects")).toBeInTheDocument();
        expect(screen.queryByText(/projects/i)).not.toBeInTheDocument();
    });
});
