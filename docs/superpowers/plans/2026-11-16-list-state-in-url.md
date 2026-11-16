# List Page State in the URL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Every UI list/search page keeps its filter chips, page, and page size (plus page-specific extras
such as date range and sort) in the URL query string, so browser Back/Forward, reload, and shared links
restore the exact view.

**Architecture:** A pure codec (`utils/listUrlState.ts`) converts between `URLSearchParams` and a
`ListState` (`filters`, `page`, `perPage`). A hook (`hooks/useListUrlState.ts`) wraps react-router's
`useSearchParams`, derives the state from the URL on every render (the URL is the single source of truth —
no mirrored `useState`), and exposes setters that each push exactly one history entry. Pages swap their
`useState` for `filters`/`page`/`perPage` to the hook; everything downstream (`loadData`, chips, pagination)
is unchanged.

**Tech Stack:** React 19, react-router-dom 7.18 (`<Routes>` style, `useSearchParams`), PatternFly 6,
`@apitomy/common-ui-components` (`ChipFilterCriteria`, `ChipFilterType`), Vitest 5 + Testing Library.

**Spec:** GitHub issue [#686](https://github.com/Apitomy/apitomy-axiom/issues/686)

## Global Constraints

- Defaults are never written to the URL: `page=1`, `perPage=20` (or the page's default), no filters,
  empty extras. A page in default state has an empty query string.
- Query keys: filter chips use their `ChipFilterType.value` as the key (e.g. `status`, `label`, `type`,
  `eventSourceId`, `traceId`); paging uses `page` and `perPage`. Extras: AI Usage `from`/`to`
  (`YYYY-MM-DD`), Disk Usage `sort` (`name`|`diskUsageBytes`) and `dir` (`asc`|`desc`), MCP Servers `q`.
- Multi-value filters repeat the key (`?label=a&label=b`), in chip order. Single-value filters: last wins.
- Each committed user action pushes one history entry. Changing filters, page size, or an extra resets
  `page` to 1 in the same navigation (never two entries). Free-text typing (MCP Servers `q`) uses
  `replace: true`.
- Invalid URL values are ignored when parsing (fall back to defaults / drop the chip); parsing never
  rewrites the URL.
- Query params the hook does not own (`taskId` on Tasks, `task` on Inbox) must be preserved untouched.
- Out of scope: Subjects "show inactive" toggle (stays in localStorage); pages without filters/paging.
- Code style: 4-space indentation, named exports, `/** */` doc comment on every exported symbol, explicit
  types, co-located `*.test.ts(x)` files.
- Run UI tests from `ui/`: `npx vitest run <path>`; full check: `npm test && npm run build`.
- Commit messages: imperative, reference `#686`, no AI attribution.

## File Structure

| File | Responsibility |
|---|---|
| `ui/src/utils/listUrlState.ts` (create) | Pure parse/write of `ListState` ⇄ `URLSearchParams` |
| `ui/src/utils/listUrlState.test.ts` (create) | Codec unit tests |
| `ui/src/hooks/useListUrlState.ts` (create) | React hook binding the codec to the router |
| `ui/src/hooks/useListUrlState.test.tsx` (create) | Hook tests incl. history (Back) behaviour |
| `ui/src/pages/*Page.tsx` (modify) | Replace local list state with the hook |
| `ui/src/pages/*Page.test.tsx` (modify) | One "loads state from URL" test per page |
| `docs/user-guide/…` (modify, Task 9) | Mention shareable/bookmarkable list URLs |

---

### Task 1: URL codec

**Files:**
- Create: `ui/src/utils/listUrlState.ts`
- Test: `ui/src/utils/listUrlState.test.ts`

**Interfaces:**
- Produces:
  - `const PAGE_PARAM = "page"`, `const PER_PAGE_PARAM = "perPage"`,
    `const PER_PAGE_OPTIONS: readonly number[] = [10, 20, 50, 100]`
  - `interface ListState { filters: ChipFilterCriteria[]; page: number; perPage: number }`
  - `interface ListStateConfig { filterTypes: ChipFilterType[]; multiValueFilters?: ReadonlySet<string>;
    defaultPerPage?: number }`
  - `parseListState(params: URLSearchParams, config: ListStateConfig): ListState`
  - `writeListState(current: URLSearchParams, state: ListState, config: ListStateConfig): URLSearchParams`

- [ ] **Step 1: Write the failing tests**

```ts
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
```

- [ ] **Step 2: Run to verify failure**

Run (from `ui/`): `npx vitest run src/utils/listUrlState.test.ts`
Expected: FAIL — cannot resolve `./listUrlState`.

- [ ] **Step 3: Implement**

```ts
import type { ChipFilterCriteria, ChipFilterType } from "@apitomy/common-ui-components";

/** Query parameter holding the 1-based page number. */
export const PAGE_PARAM: string = "page";
/** Query parameter holding the page size. */
export const PER_PAGE_PARAM: string = "perPage";
/** Page sizes accepted from the URL (PatternFly Pagination defaults). */
export const PER_PAGE_OPTIONS: readonly number[] = [10, 20, 50, 100];

const DEFAULT_PER_PAGE: number = 20;

/** The URL-backed state of a list page. */
export interface ListState {
    filters: ChipFilterCriteria[];
    page: number;
    perPage: number;
}

/** Describes which query parameters a list page owns. */
export interface ListStateConfig {
    /** Filter chip types; each type's `value` is its query key. */
    filterTypes: ChipFilterType[];
    /** Filter keys that may appear several times; all others keep only their last value. */
    multiValueFilters?: ReadonlySet<string>;
    /** Page size when the URL has none (default 20). */
    defaultPerPage?: number;
}

/**
 * Parses a positive integer, or returns null.
 *
 * @param raw the raw value
 * @returns the integer, or null when absent or invalid
 */
function parsePositiveInt(raw: string | null): number | null {
    if (raw === null || !/^\d+$/.test(raw)) {
        return null;
    }
    const n: number = Number(raw);
    return n >= 1 ? n : null;
}

/**
 * Reads list state from query parameters. Unknown keys, blank values, values not among a filter type's
 * (loaded) options, and invalid paging are ignored.
 *
 * @param params the current query parameters
 * @param config the page's list configuration
 * @returns the list state
 */
export function parseListState(params: URLSearchParams, config: ListStateConfig): ListState {
    const byKey: Map<string, ChipFilterType> = new Map(
        config.filterTypes.map((t: ChipFilterType) => [String(t.value), t]));
    const multi: ReadonlySet<string> = config.multiValueFilters ?? new Set<string>();
    let filters: ChipFilterCriteria[] = [];
    params.forEach((raw: string, key: string) => {
        const filterBy: ChipFilterType | undefined = byKey.get(key);
        const value: string = raw.trim();
        if (!filterBy || !value) {
            return;
        }
        if (filterBy.options && filterBy.options.length > 0
            && !filterBy.options.some((o) => o.value === value)) {
            return;
        }
        if (multi.has(key)) {
            if (filters.some((f) => f.filterBy.value === key && f.filterValue === value)) {
                return;
            }
        } else {
            filters = filters.filter((f) => f.filterBy.value !== key);
        }
        filters.push({ filterBy, filterValue: value });
    });
    const defaultPerPage: number = config.defaultPerPage ?? DEFAULT_PER_PAGE;
    const perPage: number | null = parsePositiveInt(params.get(PER_PAGE_PARAM));
    return {
        filters,
        page: parsePositiveInt(params.get(PAGE_PARAM)) ?? 1,
        perPage: perPage !== null && PER_PAGE_OPTIONS.includes(perPage) ? perPage : defaultPerPage,
    };
}

/**
 * Writes list state into a copy of the current query parameters, replacing every key the list owns and
 * preserving all others. Default values are omitted.
 *
 * @param current the current query parameters (not modified)
 * @param state the state to write
 * @param config the page's list configuration
 * @returns the new query parameters
 */
export function writeListState(current: URLSearchParams, state: ListState,
    config: ListStateConfig): URLSearchParams {
    const next: URLSearchParams = new URLSearchParams(current);
    config.filterTypes.forEach((t: ChipFilterType) => next.delete(String(t.value)));
    next.delete(PAGE_PARAM);
    next.delete(PER_PAGE_PARAM);
    state.filters.forEach((f: ChipFilterCriteria) => next.append(String(f.filterBy.value), f.filterValue));
    if (state.page !== 1) {
        next.set(PAGE_PARAM, String(state.page));
    }
    if (state.perPage !== (config.defaultPerPage ?? DEFAULT_PER_PAGE)) {
        next.set(PER_PAGE_PARAM, String(state.perPage));
    }
    return next;
}
```

- [ ] **Step 4: Run to verify pass**

Run: `npx vitest run src/utils/listUrlState.test.ts` — Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/utils/listUrlState.ts ui/src/utils/listUrlState.test.ts
git commit -m "Add list state URL codec (#686)"
```

---

### Task 2: `useListUrlState` hook

**Files:**
- Create: `ui/src/hooks/useListUrlState.ts`
- Test: `ui/src/hooks/useListUrlState.test.tsx`

**Interfaces:**
- Consumes: `parseListState`, `writeListState`, `ListState`, `ListStateConfig` (Task 1).
- Produces:
  ```ts
  interface ListUrlState extends ListState {
      setFilters(filters: ChipFilterCriteria[]): void;        // push, page -> 1
      setPage(page: number): void;                            // push
      setPerPage(perPage: number): void;                      // push, page -> 1
      getParam(key: string): string | null;                   // read an extra param
      setParams(updates: Record<string, string | undefined>,  // set/delete extras (""/undefined deletes)
                options?: { resetPage?: boolean; replace?: boolean }): void;
  }
  function useListUrlState(config: ListStateConfig): ListUrlState;
  ```
  `config` must be referentially stable (module constant or `useMemo`).

- [ ] **Step 1: Write the failing tests**

The harness renders a probe component under a `MemoryRouter` with a `/list` and `/detail` route, exposing
the hook result and `navigate` so tests can drive history.

```tsx
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
```

- [ ] **Step 2: Run to verify failure**

Run: `npx vitest run src/hooks/useListUrlState.test.tsx` — Expected: FAIL, module not found.

- [ ] **Step 3: Implement**

```ts
import { useCallback, useMemo } from "react";
import { useSearchParams, type SetURLSearchParams } from "react-router-dom";
import type { ChipFilterCriteria } from "@apitomy/common-ui-components";
import {
    PAGE_PARAM, parseListState, writeListState, type ListState, type ListStateConfig,
} from "../utils/listUrlState";

/** List state backed by the URL, plus setters that each push one history entry. */
export interface ListUrlState extends ListState {
    /** Replaces the filter chips and returns to page 1. */
    setFilters: (filters: ChipFilterCriteria[]) => void;
    /** Moves to a page. */
    setPage: (page: number) => void;
    /** Changes the page size and returns to page 1. */
    setPerPage: (perPage: number) => void;
    /** Reads a page-specific extra query parameter. */
    getParam: (key: string) => string | null;
    /** Sets (or, for ""/undefined, deletes) extra query parameters. */
    setParams: (updates: Record<string, string | undefined>,
        options?: { resetPage?: boolean; replace?: boolean }) => void;
}

/**
 * Binds a list page's filters, paging and extra parameters to the URL query string so that browser
 * history, reload and shared links restore the view. The URL is the single source of truth.
 *
 * @param config the page's list configuration; must be referentially stable
 * @returns the current state and its setters
 */
export function useListUrlState(config: ListStateConfig): ListUrlState {
    const [searchParams, setSearchParams]: [URLSearchParams, SetURLSearchParams] = useSearchParams();
    const state: ListState = useMemo(() => parseListState(searchParams, config), [searchParams, config]);

    const commit = useCallback((patch: Partial<ListState>): void => {
        setSearchParams((prev: URLSearchParams) =>
            writeListState(prev, { ...parseListState(prev, config), ...patch }, config));
    }, [setSearchParams, config]);

    const setParams = useCallback((updates: Record<string, string | undefined>,
        options?: { resetPage?: boolean; replace?: boolean }): void => {
        setSearchParams((prev: URLSearchParams) => {
            const next: URLSearchParams = new URLSearchParams(prev);
            Object.entries(updates).forEach(([key, value]: [string, string | undefined]) => {
                if (value === undefined || value === "") {
                    next.delete(key);
                } else {
                    next.set(key, value);
                }
            });
            if (options?.resetPage) {
                next.delete(PAGE_PARAM);
            }
            return next;
        }, { replace: options?.replace ?? false });
    }, [setSearchParams]);

    return {
        ...state,
        setFilters: useCallback((filters: ChipFilterCriteria[]) => commit({ filters, page: 1 }), [commit]),
        setPage: useCallback((page: number) => commit({ page }), [commit]),
        setPerPage: useCallback((perPage: number) => commit({ perPage, page: 1 }), [commit]),
        getParam: useCallback((key: string) => searchParams.get(key), [searchParams]),
        setParams,
    };
}
```

Note: `setFilters` both sets filters and resets the page in one `setSearchParams` call, so pages must stop
calling `setPage(1)` after `setFilters(...)` (that would add a second history entry).

- [ ] **Step 4: Run to verify pass**

Run: `npx vitest run src/hooks/useListUrlState.test.tsx` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/hooks/useListUrlState.ts ui/src/hooks/useListUrlState.test.tsx
git commit -m "Add useListUrlState hook (#686)"
```

---

### Task 3: Pilot — Runs page

**Files:**
- Modify: `ui/src/pages/WorkflowRunsPage.tsx` (state at :161-164, handlers :211-240, Pagination :259-266)
- Test: `ui/src/pages/WorkflowRunsPage.test.tsx`

**Interfaces:** Consumes `useListUrlState` (Task 2).

- [ ] **Step 1: Write the failing tests** (append to the existing `describe`)

```tsx
it("loads filters and page from the URL", async () => {
    renderWithRouter(<WorkflowRunsPage />, "/?status=failed&label=a&label=b&page=2&perPage=50");
    await waitFor(() => expect(api.fetchWorkflowRuns).toHaveBeenCalledWith(2, 50,
        expect.objectContaining({ status: "failed", labels: ["a", "b"] })));
    expect(screen.getByText("Filters")).toBeInTheDocument();
});

it("restores filters when returning from a run with Back", async () => {
    render(
        <MemoryRouter initialEntries={["/runs"]}>
            <Routes>
                <Route path="/runs" element={<WorkflowRunsPage />} />
                <Route path="/runs/:runId" element={<BackButton />} />
            </Routes>
        </MemoryRouter>);
    await screen.findByText("Run A");
    await userEvent.click(screen.getAllByRole("button", { name: "Filter by status: completed" })[0]);
    await waitFor(() => expect(lastFilters().status).toBe("completed"));
    await userEvent.click(screen.getByText("Subject 2"));
    await userEvent.click(await screen.findByRole("button", { name: "back" }));
    await waitFor(() => expect(lastFilters().status).toBe("completed"));
    expect(screen.getByText("Filters")).toBeInTheDocument();
});
```

Add at file top (after imports):

```tsx
import { useNavigate } from "react-router-dom";

function BackButton() {
    const navigate = useNavigate();
    return <button onClick={() => navigate(-1)}>back</button>;
}
```

- [ ] **Step 2: Run to verify failure**

Run: `npx vitest run src/pages/WorkflowRunsPage.test.tsx`
Expected: the two new tests FAIL (first: called with `1, 20`; second: status `undefined` after Back).

- [ ] **Step 3: Implement**

Add the config constant below `MULTI_VALUE_FILTERS`:

```ts
/** URL binding of this page's filters and paging. */
const LIST_CONFIG: ListStateConfig = { filterTypes: FILTER_TYPES, multiValueFilters: MULTI_VALUE_FILTERS };
```

Imports: add `import { useListUrlState } from "../hooks/useListUrlState";` and
`import type { ListStateConfig } from "../utils/listUrlState";`.

Replace lines 161-162 and 164 (`page`, `perPage`, `filters` `useState`s) with:

```ts
const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(LIST_CONFIG);
```

In `onAddFilterCriteria`, `onRemoveFilterCriteria`, `onClearAllFilters`: delete the `setPage(1);` lines
(`setFilters` resets the page). Change the Pagination handlers to:

```tsx
onSetPage={(_e, p) => setPage(p)}
onPerPageSelect={(_e, pp) => setPerPage(pp)}
```

- [ ] **Step 4: Run tests and build**

Run: `npx vitest run src/pages/WorkflowRunsPage.test.tsx && npx tsc -b` — Expected: PASS, no type errors.

- [ ] **Step 5: Manual verification** (per AGENTS.md "Live Testing" — never ports 9191/9090)

```bash
AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data
```
Open the UI on the offset port, `/runs`: add a status chip, go to page 2, open a run, press Back →
same chip and page. Reload → same. Clear filters → URL is `/runs`. Stop the instance afterwards.

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/WorkflowRunsPage.tsx ui/src/pages/WorkflowRunsPage.test.tsx
git commit -m "Keep Runs page filters and paging in the URL (#686)"
```

---

### Task 4: Event Stream and Subjects

**Files:**
- Modify: `ui/src/pages/EventStreamPage.tsx` (:102-103, :106, handlers :160-175, Pagination)
- Modify: `ui/src/pages/SubjectsPage.tsx` (page/perPage/filters `useState`s, handlers, Pagination)
- Test: `ui/src/pages/EventStreamPage.test.tsx`, `ui/src/pages/SubjectsPage.test.tsx`

**Interfaces:** Consumes `useListUrlState`, `ListStateConfig`.

Event Stream's filter types are built asynchronously (`eventSourceId` options arrive after
`fetchAllEventSources`). Because parsing is derived on every render and accepts any value while options are
unloaded, a `?eventSourceId=jira` link works before and after options load; once loaded, an unknown source
id is silently dropped from the state (URL untouched). SSE auto-refresh is unchanged.

- [ ] **Step 1: Write the failing tests**

`EventStreamPage.test.tsx` (use the file's existing mocks; `fetchStreamEvents` signature is
`(page, perPage, type, eventSourceId, ref, actor)`):

```tsx
it("loads filters and page from the URL", async () => {
    renderWithRouter(<EventStreamPage />, "/?type=issue.opened&actor=bob&page=3");
    await waitFor(() => expect(api.fetchStreamEvents).toHaveBeenCalledWith(
        3, 20, "issue.opened", undefined, undefined, "bob"));
});
```

`SubjectsPage.test.tsx` (`fetchSubjects(page, perPage, name, active, labels)`):

```tsx
it("loads filters and page from the URL", async () => {
    renderWithRouter(<SubjectsPage />, "/?name=widget&labels=a&labels=b&page=2");
    await waitFor(() => expect(api.fetchSubjects).toHaveBeenCalledWith(
        2, 20, "widget", true, "a,b"));
});
```

Before writing the Subjects assertion, read `SubjectsPage.tsx` `loadSubjects` and match the exact argument
shapes it passes today (e.g. whether `labels` is a joined string or `undefined` when empty); the test must
assert current argument shapes, only with URL-sourced values.

- [ ] **Step 2: Run to verify failure**

Run: `npx vitest run src/pages/EventStreamPage.test.tsx src/pages/SubjectsPage.test.tsx` — new tests FAIL.

- [ ] **Step 3: Implement — Event Stream**

```ts
const listConfig: ListStateConfig = useMemo(() => ({ filterTypes }), [filterTypes]);
const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(listConfig);
```

placed directly after the existing `const filterTypes = useMemo(...)` line; delete the `page`, `perPage`
and `filters` `useState`s. In the three filter handlers delete `setPage(1);`. Pagination:
`onPerPageSelect={(_e, pp) => setPerPage(pp)}`.

- [ ] **Step 4: Implement — Subjects**

```ts
/** URL binding of this page's filters and paging. */
const LIST_CONFIG: ListStateConfig = {
    filterTypes: FILTER_TYPES,
    multiValueFilters: new Set<string>(["labels"]),
};
```

(module level, after `FILTER_TYPES`). In the component replace the `page`/`perPage`/`filters` `useState`s
with `const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(LIST_CONFIG);`.
In `onAddFilterCriteria`/`onRemoveFilterCriteria`/`onClearAllFilters`/`addLabelFilter` remove `setPage(1)`.
If the show-inactive toggle calls `setPage(1)`, keep that call (it is a single push). Pagination
`onPerPageSelect={(_e, pp) => setPerPage(pp)}`. Leave `showInactive` in localStorage.

- [ ] **Step 5: Run tests and type-check**

Run: `npx vitest run src/pages/EventStreamPage.test.tsx src/pages/SubjectsPage.test.tsx && npx tsc -b`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/EventStreamPage.tsx ui/src/pages/EventStreamPage.test.tsx \
    ui/src/pages/SubjectsPage.tsx ui/src/pages/SubjectsPage.test.tsx
git commit -m "Keep Event Stream and Subjects list state in the URL (#686)"
```

---

### Task 5: Activity Log and AI Usage (replace deep-link seeding)

**Files:**
- Modify: `ui/src/pages/ActivityLogPage.tsx` (:35 `DEEP_LINK_FILTERS`, :45-55 seeding, handlers, Pagination)
- Modify: `ui/src/pages/AiUsagePage.tsx` (:51 `DEEP_LINK_FILTERS`, :60-71 seeding + dates, :86-91
  `onThisMonth`, :140-141 clear, :215-226 date inputs, Pagination)
- Test: `ui/src/pages/ActivityLogPage.test.tsx`, `ui/src/pages/AiUsagePage.test.tsx` (create if absent,
  modelled on `WorkflowRunsPage.test.tsx` mocks)

`?traceId=` deep links keep working because `traceId` is a regular filter key; delete `DEEP_LINK_FILTERS`
and the lazy `useState` initialisers. `entryType` (Activity Log) is multi-value.

- [ ] **Step 1: Write the failing tests**

Activity Log (`fetchActivityLog(page, perPage, eventId, summary, subjectId, entryTypes, undefined, traceId)`):

```tsx
it("loads filters and page from the URL, including the traceId deep link", async () => {
    renderWithRouter(<ActivityLogPage />, "/?traceId=t1&entryType=a&entryType=b&page=2");
    await waitFor(() => expect(api.fetchActivityLog).toHaveBeenCalledWith(
        2, 20, undefined, undefined, undefined, expect.arrayContaining(["a", "b"]), undefined, "t1"));
});
```

AI Usage (`fetchUsage(page, perPage, invType, undefined, undefined, actionType, dateFrom, dateTo,
undefined, engine, model, traceId)`):

```tsx
it("loads filters, dates and page from the URL", async () => {
    renderWithRouter(<AiUsagePage />, "/?model=opus&from=2026-11-01&to=2026-11-15&page=4");
    await waitFor(() => expect(api.fetchUsage).toHaveBeenCalledWith(
        4, 20, undefined, undefined, undefined, undefined, "2026-11-01", "2026-11-15",
        undefined, undefined, "opus", undefined));
});

it("This Month sets from, clears to, and resets the page in the URL", async () => {
    renderWithRouter(<AiUsagePage />, "/?to=2026-01-31&page=3");
    await userEvent.click(await screen.findByRole("button", { name: "This Month" }));
    const now: Date = new Date();
    const from: string = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-01`;
    await waitFor(() => expect(api.fetchUsage).toHaveBeenLastCalledWith(
        1, 20, undefined, undefined, undefined, undefined, from, undefined,
        undefined, undefined, undefined, undefined));
});
```

Before finalising, check how each page currently passes empty values (`undefined` vs `""` vs `[]`) and
mirror that in the expectations.

- [ ] **Step 2: Run to verify failure** — `npx vitest run src/pages/ActivityLogPage.test.tsx src/pages/AiUsagePage.test.tsx`

- [ ] **Step 3: Implement — Activity Log**

```ts
/** URL binding of this page's filters and paging. */
const LIST_CONFIG: ListStateConfig = {
    filterTypes: FILTER_TYPES,
    multiValueFilters: new Set<string>(["entryType"]),
};
```

Replace `page`/`perPage`/`searchParams`/`filters` state with
`const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(LIST_CONFIG);`,
remove `DEEP_LINK_FILTERS` and the `useSearchParams` import if unused, drop `setPage(1)` after
`setFilters`, update Pagination's `onPerPageSelect`.

- [ ] **Step 4: Implement — AI Usage**

Same replacement with `LIST_CONFIG = { filterTypes: FILTER_TYPES }`, plus:

```ts
const { filters, page, perPage, setFilters, setPage, setPerPage, getParam, setParams } =
    useListUrlState(LIST_CONFIG);
const filterDateFrom: string = getParam("from") ?? "";
const filterDateTo: string = getParam("to") ?? "";
```

- `onThisMonth`: `setParams({ from: `${now.getFullYear()}-${month}-01`, to: undefined }, { resetPage: true });`
- Clear-all handler (:138-142): replace `setFilters([]); setFilterDateFrom(""); setFilterDateTo(""); setPage(1);`
  with a single navigation that clears filter keys and dates together:
  `setParams(Object.fromEntries([...FILTER_TYPES.map((t) => [String(t.value), undefined]),
  ["from", undefined], ["to", undefined]]), { resetPage: true });`
- Date inputs: `onChange={(_e, v) => setParams({ from: v }, { resetPage: true })}` and likewise `to`.

- [ ] **Step 5: Run tests and type-check** — `npx vitest run src/pages/ActivityLogPage.test.tsx src/pages/AiUsagePage.test.tsx && npx tsc -b`

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/ActivityLogPage.tsx ui/src/pages/ActivityLogPage.test.tsx \
    ui/src/pages/AiUsagePage.tsx ui/src/pages/AiUsagePage.test.tsx
git commit -m "Keep Activity Log and AI Usage list state in the URL (#686)"
```

---

### Task 6: Tasks, Inbox and Disk Usage (foreign params and sort)

**Files:**
- Modify: `ui/src/pages/TasksPage.tsx` (keeps `?taskId=` modal param, :61-67)
- Modify: `ui/src/pages/InboxPage.tsx` (paging only; keeps `?task=`, :90-121)
- Modify: `ui/src/pages/DiskUsagePage.tsx` (sort :41-42, :86-88)
- Test: the three co-located `*.test.tsx`

Tasks/Inbox already call `setSearchParams` for their modal params; those calls use the functional form
(`setSearchParams(prev => ...)`) — verify and convert if not — so they don't clobber list keys. Both use
`replace: true` for the modal param today; keep that.

- [ ] **Step 1: Write the failing tests**

```tsx
// TasksPage.test.tsx — fetchAllTasks(page, perPage, actionType, status)
it("loads filters and page from the URL and keeps taskId", async () => {
    renderWithRouter(<TasksPage />, "/?status=failed&page=2&taskId=7");
    await waitFor(() => expect(api.fetchAllTasks).toHaveBeenCalledWith(2, 20, undefined, expect.anything()));
});

// InboxPage.test.tsx — fetchInboxItems(page, perPage)
it("loads the page from the URL", async () => {
    renderWithRouter(<InboxPage />, "/?page=2&perPage=50");
    await waitFor(() => expect(api.fetchInboxItems).toHaveBeenCalledWith(2, 50));
});

// DiskUsagePage.test.tsx — fetchDiskUsage(page, perPage, name, sortBy, dir)
it("loads filter, sort and page from the URL", async () => {
    renderWithRouter(<DiskUsagePage />, "/?name=x&sort=diskUsageBytes&dir=desc&page=2");
    await waitFor(() => expect(api.fetchDiskUsage).toHaveBeenCalledWith(2, 20, "x", "diskUsageBytes", "desc"));
});
```

Tighten the Tasks `status` expectation to the exact shape `fetchAllTasks` receives today (read
`TasksPage.tsx:78`).

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement — Tasks**: `LIST_CONFIG = { filterTypes: FILTER_TYPES, multiValueFilters: new
  Set<string>(["status"]) }`; hook replaces `page`/`perPage`/`filters`; keep the existing
  `useSearchParams` for `taskId` (both hooks read the same URL; `writeListState` preserves `taskId`).

- [ ] **Step 4: Implement — Inbox**: `const LIST_CONFIG: ListStateConfig = { filterTypes: [] };` and
  `const { page, perPage, setPage, setPerPage } = useListUrlState(LIST_CONFIG);`.

- [ ] **Step 5: Implement — Disk Usage**

```ts
const SORT_KEYS: readonly ("name" | "diskUsageBytes")[] = ["name", "diskUsageBytes"];
```

In the component, replace `activeSortIndex`/`activeSortDirection` state with:

```ts
const sortParam: string | null = getParam("sort");
const activeSortIndex: number = Math.max(0, SORT_KEYS.indexOf(sortParam as "name" | "diskUsageBytes"));
const activeSortDirection: "asc" | "desc" = getParam("dir") === "desc" ? "desc" : "asc";
```

and in the sort handler (:86-88):

```ts
setParams({
    sort: columnIndex === 0 ? undefined : SORT_KEYS[columnIndex],
    dir: direction === "asc" ? undefined : direction,
}, { resetPage: true });
```

(`columnSortKeys` becomes `SORT_KEYS`.)

- [ ] **Step 6: Run tests and type-check**, then **commit**:

```bash
git add ui/src/pages/TasksPage.tsx ui/src/pages/TasksPage.test.tsx ui/src/pages/InboxPage.tsx \
    ui/src/pages/InboxPage.test.tsx ui/src/pages/DiskUsagePage.tsx ui/src/pages/DiskUsagePage.test.tsx
git commit -m "Keep Tasks, Inbox and Disk Usage list state in the URL (#686)"
```

---

### Task 7: Remaining chip-filter pages

**Files (modify page + co-located test each):**
- `TracesPage.tsx` — keys `traceType`, `status` (multi); `fetchTraces(page, perPage, traceType, statuses)`
- `ToolsPage.tsx` — `name`, `labels` (multi); `fetchTools(page, perPage, name, labels)`
- `ActionTypesPage.tsx` — `name`, `mode`, `labels` (multi); `fetchActionTypes(page, perPage, name, mode, labels)`
- `EventSourcesPage.tsx` — `name`, `type`; `fetchEventSources(page, perPage, name, type)`
- `SubscriptionsPage.tsx` — `name`; `fetchSubscriptions(page, perPage, name)`
- `WorkflowDefinitionsPage.tsx` — `name`; `fetchWorkflowDefinitions(page, perPage, name)`

Each is the same mechanical change. For each page, in order:

- [ ] **Step 1: Add a failing URL test**, e.g. for Tools:

```tsx
it("loads filters and page from the URL", async () => {
    renderWithRouter(<ToolsPage />, "/?name=git&page=2");
    await waitFor(() => expect(api.fetchTools).toHaveBeenCalledWith(2, 20, "git", expect.anything()));
});
```

Use each page's fetch signature above and match its current empty-value shapes exactly (read its
`loadData`). Pages with a multi-value key put two values in the URL and assert both arrive.

- [ ] **Step 2: Run to see it fail.**
- [ ] **Step 3: Apply the change:** module-level
  `const LIST_CONFIG: ListStateConfig = { filterTypes: FILTER_TYPES, multiValueFilters: new Set<string>([...]) };`
  (omit `multiValueFilters` when none); replace the three `useState`s with
  `const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(LIST_CONFIG);`;
  remove `setPage(1)` after `setFilters`; `onPerPageSelect={(_e, pp) => setPerPage(pp)}`.
- [ ] **Step 4: Run** `npx vitest run src/pages/<Page>.test.tsx && npx tsc -b`.
- [ ] **Step 5: Commit** after all six:

```bash
git add ui/src/pages/{Traces,Tools,ActionTypes,EventSources,Subscriptions,WorkflowDefinitions}Page*.tsx
git commit -m "Keep remaining list page filters and paging in the URL (#686)"
```

---

### Task 8: MCP Servers search text

**Files:**
- Modify: `ui/src/pages/McpServersPage.tsx` (:287 `search` state, :366 input)
- Test: `ui/src/pages/McpServersPage.test.tsx`

Client-side list, no paging. Typing uses `replace: true` so each keystroke does not add a history entry;
the value still survives Back (it is in the entry being returned to) and reload.

- [ ] **Step 1: Failing tests**

```tsx
it("initialises the search from ?q=", async () => {
    renderWithRouter(<McpServersPage />, "/?q=git");
    expect(await screen.findByDisplayValue("git")).toBeInTheDocument();
});
```

Add a second assertion in the same test that a server not matching `git` (from the file's existing mock
data) is not shown.

- [ ] **Step 2: Run to see it fail.**
- [ ] **Step 3: Implement**

```ts
const LIST_CONFIG: ListStateConfig = { filterTypes: [] };
// in component, replacing useState(""):
const { getParam, setParams } = useListUrlState(LIST_CONFIG);
const search: string = getParam("q") ?? "";
const setSearch = (value: string): void => setParams({ q: value }, { replace: true });
```

- [ ] **Step 4: Run tests + `npx tsc -b`; Step 5: commit**
  `git commit -m "Keep MCP Servers search text in the URL (#686)"`.

---

### Task 9: Docs and full verification

**Files:**
- Modify: the user-guide page that introduces list filtering (find with
  `rg -l -i "filter" docs/user-guide`), adding a short paragraph wrapped at 110 columns:
  list views keep filters, page, and page size in the address bar, so Back, reload, bookmarks, and shared
  links reopen the same view.
- Modify: `docs/developer-guide/` — add a "List page state" note pointing new list pages at
  `useListUrlState` (one paragraph + the 3-line usage snippet from Task 3).

- [ ] **Step 1:** Write the doc changes.
- [ ] **Step 2:** From `ui/`: `npm test && npm run build` — Expected: all tests pass, build succeeds.
- [ ] **Step 3:** Manual smoke on an isolated instance
  (`AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data`): for Runs,
  Event Stream, Subjects, AI Usage (dates), Disk Usage (sort), Tasks (`?taskId=` modal still opens), and
  Activity Log (`?traceId=` link from a trace node modal) — filter, page, open an item, Back, reload.
  Stop the instance.
- [ ] **Step 4: Commit** `git commit -m "Document URL-backed list state (#686)"`.

---

## Self-Review Notes

- Issue coverage: Back/reload/share (Tasks 2–8), defaults omitted (Task 1), push vs replace (Tasks 2, 8),
  page reset (Task 2), invalid values (Task 1), foreign params (Tasks 1, 6), `traceId` deep links (Task 5),
  multi-value repeat (Task 1), all 14 paginated pages + MCP search (Tasks 3–8), tests per page.
- AI Usage clear-all must be one `setParams` call (filters + dates), not `setFilters` + `setParams`.
- Hook result `filters` changes identity whenever the URL changes; pages derive primitive filter values
  before `useCallback` deps, so `loadData` does not refetch spuriously.
