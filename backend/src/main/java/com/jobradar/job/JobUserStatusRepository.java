package com.jobradar.job;

import com.jobradar.job.JobEnums.ApplicationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobUserStatusRepository extends JpaRepository<JobUserStatus, Long> {
    Optional<JobUserStatus> findByUserIdAndJobId(Long userId, Long jobId);
    List<JobUserStatus> findByUserIdAndJobIdIn(Long userId, Collection<Long> jobIds);
    List<JobUserStatus> findByUserIdAndStatusIn(Long userId, Collection<ApplicationStatus> statuses);
    List<JobUserStatus> findByUserIdAndAppliedAtNotNull(Long userId);

    /** Creates the row if it does not exist yet; safe when two requests do this at the same time. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into job_user_status (user_id, job_id, status) values (:userId, :jobId, 'NEW') on conflict do nothing",
            nativeQuery = true)
    void insertIfMissing(@Param("userId") Long userId, @Param("jobId") Long jobId);

    /** Locks the row so concurrent updates to the same job run one after another. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JobUserStatus s where s.userId = :userId and s.jobId = :jobId")
    Optional<JobUserStatus> findForUpdate(@Param("userId") Long userId, @Param("jobId") Long jobId);
}
