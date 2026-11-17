package com.jobradar.tracker;

import com.jobradar.config.JobRadarProperties;
import com.jobradar.job.Job;
import com.jobradar.job.JobEnums.ApplicationStatus;
import com.jobradar.job.JobRepository;
import com.jobradar.job.JobUserStatus;
import com.jobradar.job.JobUserStatusRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackerService {
    public record Card(Long jobId, String title, String companyName, String location, String applyUrl, boolean open,
                       ApplicationStatus status, Instant appliedAt, String notes, LocalDate followUpDate) {}

    public record TrackerStats(long appliedThisWeek, long totalApplied, int responseRatePercent) {}

    private static final Set<ApplicationStatus> BOARD = Set.of(ApplicationStatus.SAVED, ApplicationStatus.APPLIED,
            ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER, ApplicationStatus.REJECTED);
    private static final Set<ApplicationStatus> RESPONDED =
            Set.of(ApplicationStatus.INTERVIEW, ApplicationStatus.OFFER, ApplicationStatus.REJECTED);

    private final JobUserStatusRepository statuses;
    private final JobRepository jobs;
    private final ZoneId zone;

    public TrackerService(JobUserStatusRepository statuses, JobRepository jobs, JobRadarProperties props) {
        this.statuses = statuses;
        this.jobs = jobs;
        this.zone = ZoneId.of(props.scan().zone());
    }

    @Transactional(readOnly = true)
    public List<Card> board(Long userId) {
        List<JobUserStatus> rows = statuses.findByUserIdAndStatusIn(userId, BOARD);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Job> byId = jobs.findOwned(rows.stream().map(JobUserStatus::getJobId).toList(), userId).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        return rows.stream()
                .filter(row -> byId.containsKey(row.getJobId()))
                .map(row -> {
                    Job job = byId.get(row.getJobId());
                    return new Card(job.getId(), job.getTitle(), job.getCompany().getName(), job.getLocation(), job.getApplyUrl(),
                            job.isStatusOpen(), row.getStatus(), row.getAppliedAt(), row.getNotes(), row.getFollowUpDate());
                })
                .sorted(Comparator.comparing(Card::jobId).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public TrackerStats stats(Long userId) {
        return compute(statuses.findByUserIdAndAppliedAtNotNull(userId));
    }

    private TrackerStats compute(List<JobUserStatus> applied) {
        Instant weekStart = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
        long thisWeek = applied.stream().map(JobUserStatus::getAppliedAt).filter(Objects::nonNull).filter(at -> !at.isBefore(weekStart)).count();
        long responded = applied.stream().filter(row -> RESPONDED.contains(row.getStatus())).count();
        int rate = applied.isEmpty() ? 0 : Math.round(100f * responded / applied.size());
        return new TrackerStats(thisWeek, applied.size(), rate);
    }
}
