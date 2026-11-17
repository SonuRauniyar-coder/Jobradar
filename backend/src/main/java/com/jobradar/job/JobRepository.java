package com.jobradar.job;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    List<Job> findByCompanyId(Long companyId);

    @Query("select j from Job j join fetch j.company c join c.list l where j.id = :id and l.userId = :userId")
    Optional<Job> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select j from Job j join fetch j.company c join c.list l where j.id in :ids and l.userId = :userId")
    List<Job> findOwned(@Param("ids") Collection<Long> ids, @Param("userId") Long userId);
}
