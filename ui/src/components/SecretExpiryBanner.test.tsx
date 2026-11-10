import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SecretExpiryBanner } from "./SecretExpiryBanner";
import { renderWithRouter } from "../test/renderWithRouter";

const counts = { expiring: 0, expired: 0 };
vi.mock("../config/secretExpiry", () => ({ useSecretExpiryCounts: () => counts }));

describe("SecretExpiryBanner", () => {
    beforeEach(() => { localStorage.clear(); counts.expiring = 0; counts.expired = 0; });

    it("renders nothing when no secrets need attention", () => {
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.queryByText(/secret/i)).not.toBeInTheDocument();
    });

    it("summarizes expiring and expired secrets", () => {
        counts.expiring = 2; counts.expired = 1;
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.getByText("1 secret has expired; 2 secrets are expiring soon.")).toBeInTheDocument();
        expect(screen.getByText("View secrets")).toBeInTheDocument();
    });

    it("stays dismissed until the counts change", async () => {
        counts.expiring = 1;
        const first = renderWithRouter(<SecretExpiryBanner />);
        await userEvent.click(screen.getByRole("button", { name: /close/i }));
        expect(screen.queryByText(/expiring soon/)).not.toBeInTheDocument();
        first.unmount();
        const second = renderWithRouter(<SecretExpiryBanner />);
        expect(screen.queryByText(/expiring soon/)).not.toBeInTheDocument();
        second.unmount();
        counts.expiring = 2;
        renderWithRouter(<SecretExpiryBanner />);
        expect(screen.getByText(/expiring soon/)).toBeInTheDocument();
    });
});
