package com.finora.api.offlinesync;

import java.util.List;

/**
 * The mutation cannot be applied without a human decision.
 *
 * <p>Thrown from inside the mutation transaction, so the domain change (if any
 * had begun) is rolled back: a conflict never leaves a partial write behind.
 * No receipt is recorded either — nothing happened, so replaying the same key
 * recomputes the same answer, and is free to succeed later if the blocking
 * condition clears.
 */
public class SyncConflictException extends RuntimeException {

    private final transient ConflictType conflictType;
    private final transient Long serverVersion;
    private final transient Object serverSnapshot;
    private final transient List<ResolutionOption> resolutionOptions;

    public SyncConflictException(ConflictType conflictType, String detail, Long serverVersion,
                                 Object serverSnapshot, List<ResolutionOption> resolutionOptions) {
        super(detail);
        this.conflictType = conflictType;
        this.serverVersion = serverVersion;
        this.serverSnapshot = serverSnapshot;
        this.resolutionOptions = List.copyOf(resolutionOptions);
    }

    public ConflictType getConflictType() {
        return conflictType;
    }

    public Long getServerVersion() {
        return serverVersion;
    }

    /**
     * The resource's public representation — the same shape its own endpoint
     * returns. Never an entity, never owner ids, never receipt internals.
     */
    public Object getServerSnapshot() {
        return serverSnapshot;
    }

    public List<ResolutionOption> getResolutionOptions() {
        return resolutionOptions;
    }
}
