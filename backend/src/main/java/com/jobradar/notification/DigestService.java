package com.jobradar.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.auth.UserRepository;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.job.JobDtos.JobFilter;
import com.jobradar.job.JobDtos.JobRow;
import com.jobradar.job.JobDtos.PageDto;
import com.jobradar.job.JobEnums.Category;
import com.jobradar.job.JobService;
import com.jobradar.scan.ScanRun;
import com.jobradar.scan.ScanRunRepository;
import com.jobradar.scan.ScanService.ScanCompleted;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** After the scheduled scan, emails jobs first seen in that scan that match searches with alerts on. */
@Service
public class DigestService {
    public static final String SCHEDULED = "SCHEDULED";
    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final int MAX_JOBS_PER_SEARCH = 50;

    private final SavedSearchRepository searches;
    private final ScanRunRepository runs;
    private final UserRepository users;
    private final JobService jobs;
    private final ObjectMapper mapper;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final JobRadarProperties.Mail config;

    public DigestService(SavedSearchRepository searches, ScanRunRepository runs, UserRepository users, JobService jobs,
                         ObjectMapper mapper, ObjectProvider<JavaMailSender> mailSender, JobRadarProperties props) {
        this.searches = searches;
        this.runs = runs;
        this.users = users;
        this.jobs = jobs;
        this.mapper = mapper;
        this.mailSender = mailSender;
        this.config = props.mail();
    }

    @EventListener
    public void onScanCompleted(ScanCompleted event) {
        if (!config.enabled() || !SCHEDULED.equals(event.triggeredBy())) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        ScanRun run = runs.findById(event.runId()).orElse(null);
        if (sender == null || run == null) {
            log.warn("Digest skipped: mail is enabled but SMTP is not configured, or the run is missing");
            return;
        }
        String body = buildBody(event.userId(), run);
        if (body == null) {
            return; // nothing new; do not send an empty email
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipient(event.userId()));
        if (StringUtils.hasText(config.from())) {
            message.setFrom(config.from());
        }
        message.setSubject("JobRadar: new jobs matching your saved searches");
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailException e) {
            log.error("Could not send the digest email: {}", e.getMessage());
        }
    }

    /** Returns the email text, or null if no saved search has new matches. */
    String buildBody(Long userId, ScanRun run) {
        StringBuilder text = new StringBuilder();
        for (SavedSearch search : searches.findByUserIdAndEmailAlertTrue(userId)) {
            JobFilter filter;
            try {
                filter = mapper.readValue(search.getFiltersJson(), JobFilter.class);
            } catch (JsonProcessingException e) {
                log.warn("Skipping saved search {}: unreadable filters", search.getId());
                continue;
            }
            PageDto<JobRow> matches = jobs.findNewMatches(userId, filter, run.getStartedAt(), MAX_JOBS_PER_SEARCH);
            if (matches.content().isEmpty()) {
                continue;
            }
            text.append("== ").append(search.getName()).append(" (").append(matches.totalElements()).append(" new) ==\n\n");
            appendGroup(text, "Fresher", matches.content(), Category.FRESHER);
            appendGroup(text, "Experienced", matches.content(), Category.EXPERIENCED);
            appendGroup(text, "Unspecified experience", matches.content(), Category.UNSPECIFIED);
            if (matches.totalElements() > matches.content().size()) {
                text.append("...and ").append(matches.totalElements() - matches.content().size()).append(" more in JobRadar.\n\n");
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static void appendGroup(StringBuilder text, String label, List<JobRow> rows, Category category) {
        List<JobRow> group = rows.stream().filter(row -> row.category() == category).toList();
        if (group.isEmpty()) {
            return;
        }
        text.append(label).append(":\n");
        for (JobRow row : group) {
            text.append("- ").append(row.title()).append(" at ").append(row.companyName());
            if (row.location() != null) {
                text.append(" (").append(row.location()).append(")");
            }
            text.append("\n  ").append(row.applyUrl()).append("\n");
        }
        text.append("\n");
    }

    private String recipient(Long userId) {
        if (StringUtils.hasText(config.to())) {
            return config.to();
        }
        return users.findById(userId).orElseThrow().getEmail();
    }
}
