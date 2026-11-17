package com.jobradar.adapter;

import java.time.Instant;

/** A job as one source reported it, before classification and de-duplication. */
public record RawJob(
        String externalId,
        String title,
        String department,
        String location,
        String workplaceHint,
        String employmentType,
        String description,
        String applyUrl,
        Instant postedAt,
        String sourceUrl) {}
