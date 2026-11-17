package com.jobradar.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "scan_runs")
@Getter
@Setter
public class ScanRun {
    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";
    public static final String INTERRUPTED = "INTERRUPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    private int total;

    @Column(nullable = false)
    private int success;

    @Column(nullable = false)
    private int failed;

    @Column(name = "current_company")
    private String currentCompany;

    @Column(nullable = false)
    private String state = RUNNING;

    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;
}
