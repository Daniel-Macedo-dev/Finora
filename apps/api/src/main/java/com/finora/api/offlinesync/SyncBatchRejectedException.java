package com.finora.api.offlinesync;

/**
 * The request as a whole is unacceptable — too many operations, or too much
 * data — so nothing in it is processed.
 *
 * <p>Distinct from {@link SyncRejectedException}, which rejects one operation
 * while its neighbours proceed. This one fails the HTTP request, because the
 * client sent something it was told not to send and should fix its batching
 * rather than resolve individual results.
 */
public class SyncBatchRejectedException extends RuntimeException {

    private final transient String code;

    public SyncBatchRejectedException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
