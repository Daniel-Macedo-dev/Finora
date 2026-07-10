package com.finora.api.transaction;

import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;

final class TransactionSpecifications {

    private TransactionSpecifications() {
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
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("description")), "%" + text.toLowerCase() + "%");
    }
}
