package com.finora.api.transaction;

import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    /**
     * Mandatory root predicate of every transaction search — the service
     * always starts from this, so no filter combination can run unscoped.
     */
    static Specification<Transaction> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    static Specification<Transaction> hasType(TransactionType type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    static Specification<Transaction> hasCategory(Long categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    static Specification<Transaction> hasAccount(Long accountId) {
        return (root, query, cb) -> cb.equal(root.get("account").get("id"), accountId);
    }

    static Specification<Transaction> occurredBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> cb.between(root.get("occurredOn"), from, to);
    }

    static Specification<Transaction> descriptionContains(String text) {
        String escaped = text.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("description")), "%" + escaped + "%", '\\');
    }
}
