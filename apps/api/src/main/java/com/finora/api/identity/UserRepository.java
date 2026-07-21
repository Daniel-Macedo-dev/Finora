package com.finora.api.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Callers must pass an already-normalized email ({@link User#normalizeEmail}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findFirstByStatus(UserStatus status);

    Page<User> findAllByStatus(UserStatus status, Pageable pageable);
}
