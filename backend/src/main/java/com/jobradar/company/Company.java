package com.jobradar.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "companies")
@Getter
@Setter
public class Company {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "list_id", nullable = false)
    private CompanyList list;

    @Column(nullable = false)
    private String name;

    private String website;

    @Column(name = "career_url")
    private String careerUrl;

    @Column(name = "location_preference")
    private String locationPreference;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType = SourceType.UNKNOWN;

    /** True when the user set the source by hand, so discovery must not replace it. */
    @Column(name = "source_locked", nullable = false)
    private boolean sourceLocked;

    @Column(name = "ats_slug")
    private String atsSlug;

    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "jobs_count", nullable = false)
    private int jobsCount;
}
