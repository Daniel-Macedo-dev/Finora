package com.finora.api.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Callers must pass an already-normalized email ({@link User#normalizeEmail}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findFirstByStatus(UserStatus status);
}
