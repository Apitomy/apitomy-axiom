import { useEffect, useState } from "react";
import { FormSelect, FormSelectOption, SearchInput, Stack, StackItem } from "@patternfly/react-core";
import {
    fetchEventSource,
    fetchEventSources,
    fetchSubject,
    fetchSubjects,
    fetchSubscription,
    fetchSubscriptions,
    fetchWorkflowDefinitions,
    getWorkflowDefinition,
} from "../../config/api";

/** Entity kinds supported by {@link EntitySelectField}. */
export type EntityKind = "subject" | "workflow" | "eventSource" | "subscription";

/** Saved value of an entity field: numeric id, or string id for event sources. */
export type EntityId = number | string;

interface EntityOption {
    id: EntityId;
    name: string;
}

interface EntityLoader {
    search: (term: string) => Promise<EntityOption[]>;
    byId: (id: EntityId) => Promise<EntityOption>;
    numericId: boolean;
}

const PAGE = 50;

const LOADERS: Record<EntityKind, EntityLoader> = {
    subject: {
        search: (t) => fetchSubjects(1, PAGE, t || undefined).then((r) => r.items),
        byId: (id) => fetchSubject(Number(id)),
        numericId: true,
    },
    workflow: {
        search: (t) => fetchWorkflowDefinitions(1, PAGE, t || undefined).then((r) => r.items),
        byId: (id) => getWorkflowDefinition(Number(id)),
        numericId: true,
    },
    eventSource: {
        search: (t) => fetchEventSources(1, PAGE, t || undefined).then((r) => r.items),
        byId: (id) => fetchEventSource(String(id)),
        numericId: false,
    },
    subscription: {
        search: (t) => fetchSubscriptions(1, PAGE, t || undefined).then((r) => r.items),
        byId: (id) => fetchSubscription(Number(id)),
        numericId: true,
    },
};

/** Debounce for server-side name search. */
const SEARCH_DEBOUNCE_MS = 300;

interface EntitySelectFieldProps {
    id: string;
    kind: EntityKind;
    label: string;
    value: unknown;
    onChange: (value: EntityId | null) => void;
}

/**
 * Searchable selector for a subject, workflow definition, event source or subscription. Typing in the
 * search box filters by name on the server; the select lists matches (up to 50). The current value is
 * always shown, resolved by id, even when it is not in the search results.
 */
export function EntitySelectField({ id, kind, label, value, onChange }: EntitySelectFieldProps) {
    const loader = LOADERS[kind];
    const hasValue = value !== null && value !== undefined && value !== "" && value !== 0;
    const [search, setSearch] = useState("");
    const [options, setOptions] = useState<EntityOption[]>([]);
    const [current, setCurrent] = useState<EntityOption | null>(null);
    const [error, setError] = useState(false);

    useEffect(() => {
        let cancelled = false;
        const timer = setTimeout(() => {
            loader.search(search)
                .then((items) => { if (!cancelled) { setOptions(items.map((i) => ({ id: i.id, name: i.name }))); setError(false); } })
                .catch(() => { if (!cancelled) setError(true); });
        }, search ? SEARCH_DEBOUNCE_MS : 0);
        return () => { cancelled = true; clearTimeout(timer); };
    }, [loader, search]);

    useEffect(() => {
        let cancelled = false;
        if (!hasValue) { setCurrent(null); return undefined; }
        loader.byId(value as EntityId)
            .then((e) => { if (!cancelled) setCurrent({ id: e.id, name: e.name }); })
            .catch(() => { if (!cancelled) setCurrent({ id: value as EntityId, name: `#${String(value)} (not found)` }); });
        return () => { cancelled = true; };
    }, [loader, hasValue, value]);

    const all = [...options];
    if (current && !all.some((o) => String(o.id) === String(current.id))) all.unshift(current);

    return (
        <Stack hasGutter>
            <StackItem>
                <SearchInput aria-label={`Search ${label}`} placeholder="Search by name"
                             value={search} onChange={(_e, v) => setSearch(v)} onClear={() => setSearch("")} />
            </StackItem>
            <StackItem>
                <FormSelect id={id} aria-label={label} value={hasValue ? String(value) : ""}
                            onChange={(_e, v) => {
                                if (v === "") onChange(null);
                                else onChange(loader.numericId ? Number(v) : v);
                            }}>
                    <FormSelectOption value="" label={error ? "Failed to load" : "(none)"} />
                    {all.map((o) => (
                        <FormSelectOption key={String(o.id)} value={String(o.id)} label={o.name} />
                    ))}
                </FormSelect>
            </StackItem>
        </Stack>
    );
}
