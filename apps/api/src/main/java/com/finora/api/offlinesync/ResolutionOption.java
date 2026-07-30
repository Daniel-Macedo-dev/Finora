package com.finora.api.offlinesync;

/**
 * Actions the user may take on a conflict. The server advertises only the ones
 * that are valid for the conflict at hand; the client shows nothing else.
 */
public enum ResolutionOption {

    /** Drop the local change and adopt the server value. */
    KEEP_SERVER,

    /**
     * Re-submit the local values on top of the current server version. Requires
     * explicit confirmation and always mints a new {@code clientMutationId} —
     * the conflicting one is never reused.
     */
    APPLY_LOCAL,

    /** Open a domain form prefilled with both sides, then submit as a new mutation. */
    EDIT_AND_RETRY,

    /** Remove the local operation and keep the server state. */
    DISCARD_LOCAL
}
