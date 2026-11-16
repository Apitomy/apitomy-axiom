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
        const option = filterBy.options?.find((o) => o.value === value);
        if (filterBy.options && filterBy.options.length > 0 && !option) {
            return;
        }
        if (multi.has(key)) {
            if (filters.some((f) => f.filterBy.value === key && f.filterValue === value)) {
                return;
            }
        } else {
            filters = filters.filter((f) => f.filterBy.value !== key);
        }
        filters.push(option && option.label !== value
            ? { filterBy, filterValue: value, filterLabel: option.label }
            : { filterBy, filterValue: value });
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
