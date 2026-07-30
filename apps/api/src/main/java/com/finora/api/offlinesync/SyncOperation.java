package com.finora.api.offlinesync;

/** The closed set of operations a queued offline mutation may request. */
public enum SyncOperation {
    CREATE,
    UPDATE,
    DELETE
}
