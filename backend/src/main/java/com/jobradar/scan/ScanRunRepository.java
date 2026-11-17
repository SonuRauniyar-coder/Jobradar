package com.jobradar.scan;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanRunRepository extends JpaRepository<ScanRun, Long> {
    Optional<ScanRun> findByIdAndUserId(Long id, Long userId);

    Optional<ScanRun> findFirstByUserIdOrderByIdDesc(Long userId);

    @Modifying
    @Query("update ScanRun r set r.currentCompany = :name where r.id = :id")
    void setCurrent(@Param("id") Long id, @Param("name") String name);

    @Modifying
    @Query("update ScanRun r set r.success = r.success + :success, r.failed = r.failed + :failed where r.id = :id")
    void addResult(@Param("id") Long id, @Param("success") int success, @Param("failed") int failed);

    @Modifying
    @Query("update ScanRun r set r.state = :state, r.finishedAt = :at, r.currentCompany = null where r.id = :id")
    void finish(@Param("id") Long id, @Param("state") String state, @Param("at") Instant at);

    @Modifying
    @Query("update ScanRun r set r.state = 'INTERRUPTED', r.finishedAt = :at, r.currentCompany = null where r.state = 'RUNNING'")
    int interruptAllRunning(@Param("at") Instant at);
}
