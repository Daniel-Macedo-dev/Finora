package com.finora.api.offlinesync;

import java.util.List;

/**
 * Compares the version an offline edit was based on with the one the server
 * currently holds.
 *
 * <p>This check is the first of two lines. It catches the ordinary case — the
 * resource changed while the device was offline — and produces a conflict the
 * user can read, with the server value beside their own. JPA's own optimistic
 * locking remains the second line, covering the narrow window between this
 * comparison and the commit; both translate into the same conflict contract, so
 * the client never has to distinguish them.
 */
public final class VersionGuard {

    private VersionGuard() {
    }

    /**
     * @param serverVersion the version the resource currently holds
     * @param baseVersion   the version the offline edit saw
     * @param snapshot      the resource's public representation, shown to the user
     */
    public static void require(long serverVersion, Long baseVersion, Object snapshot, String detail) {
        if (baseVersion != null && baseVersion == serverVersion) {
            return;
        }
        throw new SyncConflictException(ConflictType.VERSION_MISMATCH, detail, serverVersion,
                snapshot, List.of(ResolutionOption.KEEP_SERVER, ResolutionOption.APPLY_LOCAL,
                        ResolutionOption.EDIT_AND_RETRY, ResolutionOption.DISCARD_LOCAL));
    }
}
