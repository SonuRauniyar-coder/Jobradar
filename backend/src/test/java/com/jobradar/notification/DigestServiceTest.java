package com.jobradar.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.auth.UserRepository;
import com.jobradar.company.SourceType;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.job.JobDtos.JobRow;
import com.jobradar.job.JobDtos.PageDto;
import com.jobradar.job.JobEnums.ApplicationStatus;
import com.jobradar.job.JobEnums.Category;
import com.jobradar.job.JobEnums.RoleCategory;
import com.jobradar.job.JobEnums.WorkMode;
import com.jobradar.job.JobService;
import com.jobradar.scan.ScanRun;
import com.jobradar.scan.ScanRunRepository;
import com.jobradar.scan.ScanService.ScanCompleted;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class DigestServiceTest {
    private final SavedSearchRepository searches = mock(SavedSearchRepository.class);
    private final ScanRunRepository runs = mock(ScanRunRepository.class);
    private final JobService jobs = mock(JobService.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private final ScanRun run = new ScanRun();

    private DigestService service(boolean enabled) {
        JobRadarProperties props = new JobRadarProperties(null, null, null, null,
                new JobRadarProperties.Mail(enabled, "from@x.co", "to@x.co"));
        return new DigestService(searches, runs, mock(UserRepository.class), jobs, new ObjectMapper(), provider, props);
    }

    private static JobRow row(String title, Category category) {
        return new JobRow(1L, 1L, "Acme", title, "Noida", WorkMode.UNKNOWN, null, null, null, null, category, false,
                RoleCategory.OTHER, null, Instant.now(), true, SourceType.LEVER, "https://apply/1", true, ApplicationStatus.NEW, null, 0);
    }

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(sender);
        when(runs.findById(5L)).thenReturn(Optional.of(run));
        SavedSearch search = new SavedSearch();
        search.setName("Java India");
        search.setFiltersJson("{\"indiaOnly\":true}");
        when(searches.findByUserIdAndEmailAlertTrue(1L)).thenReturn(List.of(search));
    }

    @Test
    void sendsNewMatchesGroupedByCategory() {
        when(jobs.findNewMatches(eq(1L), any(), eq(run.getStartedAt()), eq(50))).thenReturn(
                new PageDto<>(List.of(row("Trainee", Category.FRESHER), row("Senior Dev", Category.EXPERIENCED)), 0, 50, 2, 1));
        service(true).onScanCompleted(new ScanCompleted(5L, 1L, DigestService.SCHEDULED));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("to@x.co");
        assertThat(mail.getValue().getText()).contains("Java India (2 new)", "Fresher:", "Trainee at Acme (Noida)",
                "Experienced:", "Senior Dev", "https://apply/1");
    }

    @Test
    void doesNotSendAnEmptyDigest() {
        when(jobs.findNewMatches(any(), any(), any(), eq(50))).thenReturn(new PageDto<>(List.of(), 0, 50, 0, 0));
        service(true).onScanCompleted(new ScanCompleted(5L, 1L, DigestService.SCHEDULED));
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void manualScansAndDisabledMailSendNothing() {
        service(true).onScanCompleted(new ScanCompleted(5L, 1L, "MANUAL"));
        service(false).onScanCompleted(new ScanCompleted(5L, 1L, DigestService.SCHEDULED));
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }
}
