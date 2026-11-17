package com.jobradar.job;

import com.jobradar.company.Company;
import com.jobradar.job.JobEnums.Category;
import com.jobradar.job.JobEnums.RoleCategory;
import com.jobradar.job.JobEnums.WorkMode;
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
@Table(name = "jobs")
@Getter
@Setter
public class Job {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String title;

    private String department;
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false)
    private WorkMode workMode = WorkMode.UNKNOWN;

    @Column(name = "employment_type")
    private String employmentType;

    @Column(name = "experience_raw")
    private String experienceRaw;

    @Column(name = "min_years")
    private Integer minYears;

    @Column(name = "max_years")
    private Integer maxYears;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.UNSPECIFIED;

    @Column(name = "category_overridden", nullable = false)
    private boolean categoryOverridden;

    @Column(name = "classification_reason")
    private String classificationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_category", nullable = false)
    private RoleCategory roleCategory = RoleCategory.OTHER;

    private String skills;
    private String description;

    @Column(name = "apply_url")
    private String applyUrl;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "status_open", nullable = false)
    private boolean statusOpen = true;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;
}
