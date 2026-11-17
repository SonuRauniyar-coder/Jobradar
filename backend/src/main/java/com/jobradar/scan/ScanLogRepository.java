package com.jobradar.scan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanLogRepository extends JpaRepository<ScanLog, Long> {
    List<ScanLog> findByScanRunId(Long runId);
}
