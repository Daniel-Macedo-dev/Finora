package com.finora.api.offlinesync;

/**
 * A parent this mutation references was created offline and has not been
 * applied yet, so there is nothing to attach to.
 *
 * <p>Distinct from a rejection on purpose: the request is well-formed and will
 * succeed once the parent lands. The client normally prevents ever sending one
 * of these by ordering dependencies locally; when one does arrive, the child is
 * held rather than failed, and the parent is never fabricated.
 */
public class DependencyMissingException extends RuntimeException {

    private final transient String code;

    public DependencyMissingException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
