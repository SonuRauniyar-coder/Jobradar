package com.jobradar.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class CompanyDtos {
    private CompanyDtos() {}

    public record ListRequest(@NotBlank @Size(max = 255) String name) {}

    public record ListDto(Long id, String name, Instant createdAt, long companyCount) {}

    public record CompanyRequest(
            Long listId,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 1000) String website,
            @Size(max = 1000) String careerUrl,
            @Size(max = 255) String locationPreference,
            SourceType sourceType,
            @Size(max = 255) String atsSlug) {}

    public record CompanyDto(Long id, Long listId, String name, String website, String careerUrl,
                             String locationPreference, SourceType sourceType, boolean sourceLocked, String atsSlug,
                             Instant lastScannedAt, String lastStatus, String lastError, int jobsCount) {
        public static CompanyDto of(Company c) {
            return new CompanyDto(c.getId(), c.getList().getId(), c.getName(), c.getWebsite(), c.getCareerUrl(),
                    c.getLocationPreference(), c.getSourceType(), c.isSourceLocked(), c.getAtsSlug(),
                    c.getLastScannedAt(), c.getLastStatus(), c.getLastError(), c.getJobsCount());
        }
    }

    public enum RowStatus { VALID, ERROR, DUPLICATE }

    public record UploadRow(int row, String name, String careerUrl, String website, String locationPreference,
                            @NotNull RowStatus status, String message) {}

    public record UploadResult(List<UploadRow> rows, int valid, int errors, int duplicates, int saved,
                               boolean committed) {}
}
