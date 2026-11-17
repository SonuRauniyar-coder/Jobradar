package com.jobradar.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettings {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "profile_keywords", nullable = false)
    private String profileKeywords = "";

    @Column(name = "preferred_locations", nullable = false)
    private String preferredLocations = "";

    @Column(name = "scan_concurrency", nullable = false)
    private int scanConcurrency = 10;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private double rateLimitPerSec = 1.0;
}
