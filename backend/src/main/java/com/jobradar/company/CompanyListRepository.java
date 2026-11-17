package com.jobradar.company;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyListRepository extends JpaRepository<CompanyList, Long> {
    List<CompanyList> findByUserIdOrderByName(Long userId);
    Optional<CompanyList> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);

    /**
     * Locks the list row until the transaction ends. Every write that adds companies takes this lock first,
     * so two concurrent uploads or creates cannot both pass the duplicate and size checks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CompanyList l where l.id = :id and l.userId = :userId")
    Optional<CompanyList> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
