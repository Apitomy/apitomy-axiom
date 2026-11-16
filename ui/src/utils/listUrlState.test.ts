import { describe, expect, it } from "vitest";
import type { ChipFilterType } from "@apitomy/common-ui-components";
import { parseListState, writeListState, type ListStateConfig } from "./listUrlState";

const STATUS: ChipFilterType = { value: "status", label: "Status", testId: "s" };
const LABEL: ChipFilterType = { value: "label", label: "Label", testId: "l" };
const SOURCE: ChipFilterType = {
    value: "source", label: "Source", testId: "src",
    options: [{ value: "jira", label: "Jira" }, { value: "github", label: "GitHub" }],
};
const CONFIG: ListStateConfig = {
    filterTypes: [STATUS, LABEL, SOURCE],
    multiValueFilters: new Set<string>(["label"]),
};

describe("parseListState", () => {
    it("returns defaults for an empty query", () => {
        expect(parseListState(new URLSearchParams(""), CONFIG)).toEqual({ filters: [], page: 1, perPage: 20 });
    });

    it("reads filters in URL order, page and perPage", () => {
        const state = parseListState(new URLSearchParams("label=a&status=failed&label=b&page=3&perPage=50"), CONFIG);
        expect(state.filters.map((f) => [f.filterBy.value, f.filterValue]))
            .toEqual([["label", "a"], ["status", "failed"], ["label", "b"]]);
        expect(state.filters[1].filterBy).toBe(STATUS);
        expect(state.page).toBe(3);
        expect(state.perPage).toBe(50);
    });

    it("keeps only the last value of a single-value filter", () => {
        const state = parseListState(new URLSearchParams("status=a&status=b"), CONFIG);
        expect(state.filters.map((f) => f.filterValue)).toEqual(["b"]);
    });

    it("drops duplicate multi-value entries, blank values and unknown keys", () => {
        const state = parseListState(new URLSearchParams("label=a&label=a&status=%20&bogus=1"), CONFIG);
        expect(state.filters.map((f) => f.filterValue)).toEqual(["a"]);
    });

    it("drops values not among a filter type's options", () => {
        const state = parseListState(new URLSearchParams("source=jira&source=nope"), CONFIG);
        expect(state.filters.map((f) => f.filterValue)).toEqual(["jira"]);
    });

    it("accepts any value for an option filter whose options are not loaded yet", () => {
        const config: ListStateConfig = { filterTypes: [{ ...SOURCE, options: undefined }] };
        expect(parseListState(new URLSearchParams("source=x"), config).filters).toHaveLength(1);
    });

    it("falls back to defaults for invalid paging", () => {
        for (const q of ["page=0", "page=-2", "page=abc", "page=1.5"]) {
            expect(parseListState(new URLSearchParams(q), CONFIG).page).toBe(1);
        }
        expect(parseListState(new URLSearchParams("perPage=7"), CONFIG).perPage).toBe(20);
        expect(parseListState(new URLSearchParams(""), { ...CONFIG, defaultPerPage: 10 }).perPage).toBe(10);
    });
});

describe("writeListState", () => {
    it("omits defaults entirely", () => {
        const out = writeListState(new URLSearchParams(""), { filters: [], page: 1, perPage: 20 }, CONFIG);
        expect(out.toString()).toBe("");
    });

    it("writes filters in order, then non-default paging", () => {
        const out = writeListState(new URLSearchParams(""), {
            filters: [
                { filterBy: LABEL, filterValue: "team=core" },
                { filterBy: STATUS, filterValue: "failed" },
                { filterBy: LABEL, filterValue: "b" },
            ],
            page: 2, perPage: 50,
        }, CONFIG);
        expect([...out.entries()]).toEqual([
            ["label", "team=core"], ["status", "failed"], ["label", "b"], ["page", "2"], ["perPage", "50"],
        ]);
    });

    it("replaces owned keys but preserves foreign ones", () => {
        const out = writeListState(new URLSearchParams("taskId=9&status=old&page=4"),
            { filters: [], page: 1, perPage: 20 }, CONFIG);
        expect(out.toString()).toBe("taskId=9");
    });

    it("round-trips", () => {
        const q = "status=failed&label=a&label=b&page=3&perPage=10";
        const params = new URLSearchParams(q);
        expect(writeListState(new URLSearchParams(""), parseListState(params, CONFIG), CONFIG).toString()).toBe(q);
    });
});
