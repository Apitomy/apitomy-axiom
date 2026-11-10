import { describe, expect, it } from "vitest";
import { countExpiry, expiryLabel, isValidLocalDateTime, toApiInstant } from "./secretExpiry";
import type { Secret } from "./api";

const NOW = new Date("2026-11-10T12:00:00Z");
const s = (over: Partial<Secret>): Secret => ({ id: 1, name: "X", expiryStatus: "NONE", ...over });

describe("expiryLabel", () => {
    it("is Never for secrets that never expire", () => {
        expect(expiryLabel(s({}), NOW)).toBe("Never");
    });
    it("shows days remaining while expiring", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRING", expiresOn: "2026-11-15T12:00:00Z" }), NOW))
            .toBe("Expires in 5d");
    });
    it("shows hours on the last day", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRING", expiresOn: "2026-11-10T15:00:00Z" }), NOW))
            .toBe("Expires in 3h");
    });
    it("shows Expired", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRED", expiresOn: "2026-11-01T00:00:00Z" }), NOW))
            .toBe("Expired");
    });
});

describe("countExpiry", () => {
    it("counts expiring and expired secrets", () => {
        expect(countExpiry([
            s({ expiryStatus: "EXPIRING" }), s({ expiryStatus: "EXPIRED" }),
            s({ expiryStatus: "EXPIRED" }), s({ expiryStatus: "VALID" }),
        ])).toEqual({ expiring: 1, expired: 2 });
    });
});

describe("toApiInstant", () => {
    it("formats a local datetime as a UTC instant without fractional seconds", () => {
        const local = "2026-12-31T23:59";
        const result = toApiInstant(local);
        expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
        expect(new Date(result).getTime()).toBe(new Date(local).getTime());
    });
});

describe("expiryLabel with stale status", () => {
    it("is Expired when expiresOn has passed even if status says EXPIRING", () => {
        expect(expiryLabel(s({ expiryStatus: "EXPIRING", expiresOn: "2026-11-10T11:00:00Z" }), NOW))
            .toBe("Expired");
    });
});

describe("isValidLocalDateTime", () => {
    it("accepts a parseable datetime-local value", () => {
        expect(isValidLocalDateTime("2026-12-31T00:39")).toBe(true);
    });
    it("rejects unparseable values", () => {
        expect(isValidLocalDateTime("202610-12-31T00:39")).toBe(false);
        expect(isValidLocalDateTime("")).toBe(false);
    });
});
