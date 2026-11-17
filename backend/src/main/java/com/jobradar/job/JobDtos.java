package com.jobradar.job;

import com.jobradar.company.SourceType;
import com.jobradar.job.JobEnums.ApplicationStatus;
import com.jobradar.job.JobEnums.Category;
import com.jobradar.job.JobEnums.RoleCategory;
import com.jobradar.job.JobEnums.WorkMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public final class JobDtos {
    private JobDtos() {}

    /** Also the stored shape of a saved search. */
    public record JobFilter(
            Category category,
            String location,
            Boolean indiaOnly,
            String q,
            String company,
            WorkMode workMode,
            RoleCategory roleCategory,
            ApplicationStatus status,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {}

    public record JobRow(Long id, Long companyId, String companyName, String title, String location, WorkMode workMode,
                         String employmentType, String experienceRaw, Integer minYears, Integer maxYears, Category category,
                         boolean categoryOverridden, RoleCategory roleCategory, Instant postedAt, Instant firstSeenAt,
                         boolean isNew, SourceType source, String applyUrl, boolean open, ApplicationStatus status,
                         Instant appliedAt, int matchScore) {}

    public record JobDetail(JobRow job, String department, String description, List<String> skills,
                            String classificationReason, String sourceUrl, List<String> matchedKeywords,
                            String notes, LocalDate followUpDate) {}

    public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record CategoryUpdate(@NotNull Category category) {}

    /**
     * Null status leaves it unchanged; null notes leave them unchanged; clearFollowUp removes the date.
     * When expectedNotes is set (use "" for no notes), the notes are only replaced if they still have that value,
     * so an edit made from an outdated page is rejected with 409 instead of silently overwriting a newer one.
     */
    public record StatusUpdate(ApplicationStatus status, @Size(max = 4000) String notes, LocalDate followUpDate,
                               boolean clearFollowUp, String expectedNotes) {}

    /** Rows for an export, plus how many jobs matched in total so a capped export can say so. */
    public record ExportRows(List<JobRow> rows, long totalMatching) {}

    public record DashboardStats(long totalCompanies, long companiesScanned, long openJobs, long fresherJobs,
                                 long experiencedJobs, long newJobs24h, long failedCompanies) {}
}
