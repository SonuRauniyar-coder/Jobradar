package com.jobradar.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "scan_logs")
@Getter
@Setter
public class ScanLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scan_run_id", nullable = false)
    private Long scanRunId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false)
    private String status;

    @Column(name = "jobs_found", nullable = false)
    private int jobsFound;

    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
}
