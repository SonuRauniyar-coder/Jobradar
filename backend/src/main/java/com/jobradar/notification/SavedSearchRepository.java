package com.jobradar.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {
    List<SavedSearch> findByUserIdOrderByName(Long userId);
    List<SavedSearch> findByUserIdAndEmailAlertTrue(Long userId);
    Optional<SavedSearch> findByIdAndUserId(Long id, Long userId);
}
