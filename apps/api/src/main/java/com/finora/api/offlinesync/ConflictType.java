package com.finora.api.offlinesync;

/**
 * Typed reasons a mutation could not be applied without a human decision.
 *
 * <p>Finora never merges concurrent financial changes automatically and never
 * applies last-write-wins: a client timestamp is not authoritative, so a
 * conflict always surfaces to the user with the server value beside the local
 * one.
 */
public enum ConflictType {

    /** The resource changed since the version the offline edit was based on. */
    VERSION_MISMATCH,

    /** The resource no longer exists on the server. Never recreated silently. */
    REMOTE_DELETED,

    /**
     * A uniqueness rule already holds for an equivalent resource (for example a
     * budget for the same category and month created on another device). A
     * CREATE is never silently converted into an UPDATE.
     */
    RESOURCE_ALREADY_EXISTS,

    /**
     * The same idempotency key was submitted with a different request
     * fingerprint. The stored receipt is never overwritten and the new payload
     * is never applied.
     */
    IDEMPOTENCY_KEY_REUSED,

    /** A referenced parent resource changed in a way the mutation cannot absorb. */
    DEPENDENCY_CHANGED
}
