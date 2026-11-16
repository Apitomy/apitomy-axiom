import { useCallback, useMemo } from "react";
import { useSearchParams, type SetURLSearchParams } from "react-router-dom";
import type { ChipFilterCriteria } from "@apitomy/common-ui-components";
import {
    PAGE_PARAM, parseListState, writeListState, type ListState, type ListStateConfig,
} from "../utils/listUrlState";

/** List state backed by the URL, plus setters that each push at most one history entry (none when the URL
 * would not change). */
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

    /**
     * Navigates to the given query parameters, unless they equal the current ones: React Router would
     * otherwise push a duplicate history entry and the user's next Back press would appear to do nothing.
     */
    const navigateTo = useCallback((next: URLSearchParams, replace: boolean): void => {
        if (next.toString() !== searchParams.toString()) {
            setSearchParams(next, { replace });
        }
    }, [searchParams, setSearchParams]);

    const commit = useCallback((patch: Partial<ListState>): void => {
        navigateTo(writeListState(searchParams,
            { ...parseListState(searchParams, config), ...patch }, config), false);
    }, [navigateTo, searchParams, config]);

    const setParams = useCallback((updates: Record<string, string | undefined>,
        options?: { resetPage?: boolean; replace?: boolean }): void => {
        const next: URLSearchParams = new URLSearchParams(searchParams);
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
        navigateTo(next, options?.replace ?? false);
    }, [navigateTo, searchParams]);

    return {
        ...state,
        setFilters: useCallback((filters: ChipFilterCriteria[]) => commit({ filters, page: 1 }), [commit]),
        setPage: useCallback((page: number) => commit({ page }), [commit]),
        setPerPage: useCallback((perPage: number) => commit({ perPage, page: 1 }), [commit]),
        getParam: useCallback((key: string) => searchParams.get(key), [searchParams]),
        setParams,
    };
}
