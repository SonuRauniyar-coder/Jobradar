package com.jobradar.job;

import com.jobradar.classifier.JobAttributes;
import com.jobradar.common.ApiException;
import com.jobradar.company.CompanyRepository;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.job.JobDtos.DashboardStats;
import com.jobradar.job.JobDtos.JobDetail;
import com.jobradar.job.JobDtos.JobFilter;
import com.jobradar.job.JobDtos.JobRow;
import com.jobradar.job.JobDtos.PageDto;
import com.jobradar.job.JobDtos.StatusUpdate;
import com.jobradar.job.JobEnums.ApplicationStatus;
import com.jobradar.job.JobEnums.Category;
import com.jobradar.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class JobService {
    public static final int EXPORT_LIMIT = 10_000;
    private static final int MAX_PAGE_SIZE = 200;
    private static final Duration NEW_WINDOW = Duration.ofHours(24);
    private static final Set<ApplicationStatus> COUNTS_AS_APPLIED =
            Set.of(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER, ApplicationStatus.REJECTED);
    private static final Map<String, String> SORTABLE = Map.of(
            "company", "company.name", "title", "title", "location", "location", "experience", "minYears",
            "category", "category", "postedAt", "postedAt", "firstSeenAt", "firstSeenAt", "source", "company.sourceType");

    private final JobRepository jobs;
    private final JobUserStatusRepository statuses;
    private final CompanyRepository companies;
    private final SettingsService settings;
    private final ZoneId zone;

    public JobService(JobRepository jobs, JobUserStatusRepository statuses, CompanyRepository companies,
                      SettingsService settings, JobRadarProperties props) {
        this.jobs = jobs;
        this.statuses = statuses;
        this.companies = companies;
        this.settings = settings;
        this.zone = ZoneId.of(props.scan().zone());
    }

    @Transactional(readOnly = true)
    public PageDto<JobRow> search(Long userId, JobFilter filter, int page, int size, String sort) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // The database offset is an int, so pages beyond that range are simply empty.
        int maxPage = (Integer.MAX_VALUE - pageSize) / pageSize;
        return search(userId, filter, PageRequest.of(Math.min(Math.max(page, 0), maxPage), pageSize), parseSort(sort), null);
    }

    /** Export is capped, with the newest jobs first unless the sort says otherwise. */
    @Transactional(readOnly = true)
    public JobDtos.ExportRows searchForExport(Long userId, JobFilter filter, String sort) {
        PageDto<JobRow> page = search(userId, filter, PageRequest.of(0, EXPORT_LIMIT), parseSort(sort), null);
        return new JobDtos.ExportRows(page.content(), page.totalElements());
    }

    /** Open jobs matching a filter that were first seen at or after the given time. */
    @Transactional(readOnly = true)
    public PageDto<JobRow> findNewMatches(Long userId, JobFilter filter, Instant since, int limit) {
        return search(userId, filter, PageRequest.of(0, limit), parseSort(null), since);
    }

    private PageDto<JobRow> search(Long userId, JobFilter filter, Pageable pageable, Sort sort, Instant firstSeenSince) {
        Page<Job> result = jobs.findAll(JobSpecifications.matching(userId, filter, zone, firstSeenSince, sort), pageable);
        List<Long> ids = result.getContent().stream().map(Job::getId).toList();
        Map<Long, JobUserStatus> statusByJob = ids.isEmpty() ? Map.of()
                : statuses.findByUserIdAndJobIdIn(userId, ids).stream()
                        .collect(Collectors.toMap(JobUserStatus::getJobId, Function.identity()));
        List<String> keywords = settings.profileKeywords(userId);
        List<JobRow> rows = result.getContent().stream()
                .map(job -> toRow(job, statusByJob.get(job.getId()), keywords))
                .toList();
        return new PageDto<>(rows, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public JobDetail detail(Long userId, Long id) {
        Job job = require(userId, id);
        JobUserStatus status = statuses.findByUserIdAndJobId(userId, id).orElse(null);
        List<String> keywords = settings.profileKeywords(userId);
        List<String> matched = JobAttributes.matchedKeywords(keywords, job.getTitle(), job.getSkills(), job.getDescription());
        List<String> skills = StringUtils.hasText(job.getSkills()) ? Arrays.asList(job.getSkills().split(", ")) : List.of();
        return new JobDetail(toRow(job, status, keywords), job.getDepartment(), job.getDescription(), skills,
                job.getClassificationReason(), job.getSourceUrl(), matched,
                status == null ? null : status.getNotes(), status == null ? null : status.getFollowUpDate());
    }

    @Transactional
    public JobDetail setCategory(Long userId, Long id, Category category) {
        Job job = require(userId, id);
        job.setCategory(category);
        job.setCategoryOverridden(true);
        job.setClassificationReason("Set manually");
        return detail(userId, id);
    }

    @Transactional
    public JobDetail updateStatus(Long userId, Long id, StatusUpdate update) {
        require(userId, id);
        // Insert-if-missing plus a row lock lets a double click or two tabs update the same job safely.
        statuses.insertIfMissing(userId, id);
        JobUserStatus status = statuses.findForUpdate(userId, id).orElseThrow();
        if (update.notes() != null && update.expectedNotes() != null) {
            String current = status.getNotes() == null ? "" : status.getNotes();
            if (!current.equals(update.expectedNotes())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "These notes were changed somewhere else. Reload the page to see the latest version.");
            }
        }
        if (update.status() != null) {
            status.setStatus(update.status());
            if (COUNTS_AS_APPLIED.contains(update.status())) {
                if (status.getAppliedAt() == null && update.status() != ApplicationStatus.REJECTED) {
                    status.setAppliedAt(Instant.now());
                }
            } else {
                status.setAppliedAt(null); // moved back before "applied", e.g. an accidental click
            }
        }
        if (update.notes() != null) {
            status.setNotes(update.notes().isBlank() ? null : update.notes());
        }
        if (update.clearFollowUp()) {
            status.setFollowUpDate(null);
        } else if (update.followUpDate() != null) {
            status.setFollowUpDate(update.followUpDate());
        }
        statuses.save(status);
        return detail(userId, id);
    }

    @Transactional(readOnly = true)
    public DashboardStats stats(Long userId) {
        var all = companies.findByListUserIdOrderByName(userId);
        long scanned = all.stream().filter(c -> c.getLastScannedAt() != null).count();
        long failed = all.stream().filter(c -> "FAILED".equals(c.getLastStatus()) || "BLOCKED".equals(c.getLastStatus())).count();
        // Counted with the same rules as the Jobs page, so the numbers match its tabs:
        // ignored jobs are left out, and Unspecified jobs count under both Fresher and Experienced.
        return new DashboardStats(all.size(), scanned, count(userId, null, null),
                count(userId, Category.FRESHER, null), count(userId, Category.EXPERIENCED, null),
                count(userId, null, Instant.now().minus(NEW_WINDOW)), failed);
    }

    private long count(Long userId, Category category, Instant firstSeenSince) {
        JobFilter filter = new JobFilter(category, null, null, null, null, null, null, null, null, null);
        return jobs.count(JobSpecifications.matching(userId, filter, zone, firstSeenSince, Sort.unsorted()));
    }

    private Job require(Long userId, Long id) {
        return jobs.findOwned(id, userId).orElseThrow(() -> ApiException.notFound("Job"));
    }

    private JobRow toRow(Job job, JobUserStatus status, List<String> keywords) {
        List<String> matched = JobAttributes.matchedKeywords(keywords, job.getTitle(), job.getSkills(), job.getDescription());
        return new JobRow(job.getId(), job.getCompany().getId(), job.getCompany().getName(), job.getTitle(), job.getLocation(),
                job.getWorkMode(), job.getEmploymentType(), job.getExperienceRaw(), job.getMinYears(), job.getMaxYears(),
                job.getCategory(), job.isCategoryOverridden(), job.getRoleCategory(), job.getPostedAt(), job.getFirstSeenAt(),
                job.getFirstSeenAt().isAfter(Instant.now().minus(NEW_WINDOW)), job.getCompany().getSourceType(), job.getApplyUrl(),
                job.isStatusOpen(), status == null ? ApplicationStatus.NEW : status.getStatus(),
                status == null ? null : status.getAppliedAt(), JobAttributes.matchScore(keywords, matched));
    }

    /** Only whitelisted fields can be sorted; "postedAt,desc" is the format and also the default. */
    private static Sort parseSort(String sort) {
        Sort fallback = Sort.by(Sort.Order.desc("postedAt"), Sort.Order.desc("id"));
        if (!StringUtils.hasText(sort)) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String field = SORTABLE.get(parts[0]);
        if (field == null) {
            throw ApiException.badRequest("Cannot sort by " + parts[0]);
        }
        if (parts.length > 2 || (parts.length == 2
                && !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc")))) {
            throw ApiException.badRequest("Sort must look like \"field\" or \"field,asc\" or \"field,desc\"");
        }
        boolean descending = parts.length > 1 && parts[1].equalsIgnoreCase("desc");
        return Sort.by(descending ? Sort.Order.desc(field) : Sort.Order.asc(field)).and(Sort.by(Sort.Order.desc("id")));
    }
}
