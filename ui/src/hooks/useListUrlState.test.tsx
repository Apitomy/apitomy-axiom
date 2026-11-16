import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from "react-router-dom";
import type { ChipFilterType } from "@apitomy/common-ui-components";
import type { ListStateConfig } from "../utils/listUrlState";
import { useListUrlState, type ListUrlState } from "./useListUrlState";

const STATUS: ChipFilterType = { value: "status", label: "Status", testId: "s" };
const CONFIG: ListStateConfig = { filterTypes: [STATUS] };

let hook: ListUrlState;
let navigate: NavigateFunction;

function Probe() {
    hook = useListUrlState(CONFIG);
    navigate = useNavigate();
    const location = useLocation();
    return <p data-testid="loc">{location.pathname + location.search}</p>;
}

function Away() {
    navigate = useNavigate();
    return <p data-testid="loc">away</p>;
}

function setup(path: string): void {
    render(
        <MemoryRouter initialEntries={[path]}>
            <Routes>
                <Route path="/list" element={<Probe />} />
                <Route path="/detail" element={<Away />} />
            </Routes>
        </MemoryRouter>);
}

const loc = (): string => screen.getByTestId("loc").textContent ?? "";

describe("useListUrlState", () => {
    it("derives state from the URL", () => {
        setup("/list?status=failed&page=2");
        expect(hook.filters.map((f) => f.filterValue)).toEqual(["failed"]);
        expect(hook.page).toBe(2);
    });

    it("setFilters writes filters and resets page in one entry", () => {
        setup("/list?page=4");
        act(() => hook.setFilters([{ filterBy: STATUS, filterValue: "ok" }]));
        expect(loc()).toBe("/list?status=ok");
        act(() => navigate(-1));
        expect(loc()).toBe("/list?page=4");
    });

    it("setPerPage resets page; setPage keeps filters", () => {
        setup("/list?status=x&page=3");
        act(() => hook.setPage(5));
        expect(loc()).toBe("/list?status=x&page=5");
        act(() => hook.setPerPage(50));
        expect(loc()).toBe("/list?status=x&perPage=50");
    });

    it("restores filters and page after navigating away and back", () => {
        setup("/list");
        act(() => hook.setFilters([{ filterBy: STATUS, filterValue: "failed" }]));
        act(() => hook.setPage(3));
        act(() => navigate("/detail"));
        expect(loc()).toBe("away");
        act(() => navigate(-1));
        expect(hook.filters.map((f) => f.filterValue)).toEqual(["failed"]);
        expect(hook.page).toBe(3);
    });

    it("setParams sets and deletes extras, optionally resetting page or replacing", () => {
        setup("/list?page=2&keep=1");
        act(() => hook.setParams({ from: "2026-11-01" }, { resetPage: true }));
        expect(loc()).toBe("/list?keep=1&from=2026-11-01");
        expect(hook.getParam("from")).toBe("2026-11-01");
        act(() => hook.setParams({ from: "" }, { replace: true }));
        expect(loc()).toBe("/list?keep=1");
        act(() => navigate(-1));
        expect(loc()).toBe("/list?page=2&keep=1");
    });
});
