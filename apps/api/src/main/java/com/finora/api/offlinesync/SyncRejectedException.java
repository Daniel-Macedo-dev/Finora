package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.FieldError;
import java.util.List;

/**
 * The mutation is permanently invalid: bad payload, a rule the domain refuses,
 * a resource the owner does not own, or a protected record that offline
 * synchronization is not allowed to touch.
 *
 * <p>The client must never retry these automatically — the same request would
 * fail identically forever. They surface in the synchronization center as
 * actionable failures the user edits or discards.
 */
public class SyncRejectedException extends RuntimeException {

    private final transient String code;
    private final transient List<FieldError> fieldErrors;

    public SyncRejectedException(String code, String detail) {
        this(code, detail, List.of());
    }

    public SyncRejectedException(String code, String detail, List<FieldError> fieldErrors) {
        super(detail);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public String getCode() {
        return code;
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }
}
