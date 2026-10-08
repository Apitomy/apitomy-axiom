/** Behaviours a session template enables. Mirrors the backend `SessionCapability` JSON names. */
export type SessionCapability = "config-items" | "validation-feedback" | "composition";

/**
 * Returns whether a session or template declares a capability.
 *
 * @param target a session info or template object, possibly missing
 * @param capability the capability to look for
 * @returns true if `target.capabilities` contains `capability`
 */
export function hasCapability(
    target: { capabilities?: string[] } | null | undefined,
    capability: SessionCapability,
): boolean {
    return target?.capabilities?.includes(capability) ?? false;
}
