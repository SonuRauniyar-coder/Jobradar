package com.jobradar.scan;

import com.jobradar.common.ApiException;
import com.jobradar.company.Company;
import com.jobradar.company.CompanyRepository;
import com.jobradar.settings.SettingsService;
import com.jobradar.settings.UserSettings;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ScanService {
    public record ScanRequest(Long listId, List<Long> companyIds, boolean failedOnly) {}

    public record ScanProgress(Long id, String state, int total, int done, int success, int failed,
                               String currentCompany, java.time.Instant startedAt, java.time.Instant finishedAt,
                               String triggeredBy) {
        static ScanProgress of(ScanRun r) {
            return new ScanProgress(r.getId(), r.getState(), r.getTotal(), r.getSuccess() + r.getFailed(),
                    r.getSuccess(), r.getFailed(), r.getCurrentCompany(), r.getStartedAt(), r.getFinishedAt(),
                    r.getTriggeredBy());
        }
    }

    /** Published after a bulk or single scan ends; the digest listens for it. */
    public record ScanCompleted(Long runId, Long userId, String triggeredBy) {}

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final Set<String> FAILED_STATUSES = Set.of("FAILED", "BLOCKED");

    private final CompanyRepository companies;
    private final ScanRunRepository runs;
    private final ScanRecorder recorder;
    private final CompanyScanner scanner;
    private final SettingsService settings;
    private final DomainRateLimiter limiter;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean scanActive = new AtomicBoolean();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "scan-coordinator");
        thread.setDaemon(true);
        return thread;
    });

    public ScanService(CompanyRepository companies, ScanRunRepository runs, ScanRecorder recorder, CompanyScanner scanner,
                       SettingsService settings, DomainRateLimiter limiter, ApplicationEventPublisher events) {
        this.companies = companies;
        this.runs = runs;
        this.recorder = recorder;
        this.scanner = scanner;
        this.settings = settings;
        this.limiter = limiter;
        this.events = events;
    }

    /** A run left RUNNING by a previous process can never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void clearStaleRuns() {
        int stale = recorder.interruptStaleRuns();
        if (stale > 0) {
            log.warn("Marked {} unfinished scan run(s) as INTERRUPTED", stale);
        }
    }

    /** Queues the scan and returns at once; the work happens on background threads. */
    public ScanProgress start(Long userId, ScanRequest request, String triggeredBy) {
        List<Company> targets = resolveTargets(userId, request);
        if (targets.isEmpty()) {
            throw ApiException.badRequest(request.failedOnly() ? "No failed companies to rescan" : "No companies to scan");
        }
        if (!scanActive.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "A scan is already running");
        }
        try {
            Long runId = recorder.createRun(userId, targets.size(), triggeredBy);
            List<Long> ids = targets.stream().map(Company::getId).toList();
            UserSettings config = settings.get(userId);
            coordinator.execute(() -> runAll(runId, userId, ids, config, triggeredBy));
            return ScanProgress.of(runs.findById(runId).orElseThrow());
        } catch (RuntimeException e) {
            scanActive.set(false);
            throw e;
        }
    }

    public ScanProgress progress(Long userId, Long runId) {
        return runs.findByIdAndUserId(runId, userId).map(ScanProgress::of)
                .orElseThrow(() -> ApiException.notFound("Scan"));
    }

    public Optional<ScanProgress> latest(Long userId) {
        return runs.findFirstByUserIdOrderByIdDesc(userId).map(ScanProgress::of);
    }

    private List<Company> resolveTargets(Long userId, ScanRequest request) {
        List<Company> targets;
        if (request.companyIds() != null && !request.companyIds().isEmpty()) {
            targets = companies.findByIdInAndListUserId(request.companyIds(), userId);
        } else if (request.listId() != null) {
            targets = companies.findByListIdAndListUserIdOrderByName(request.listId(), userId);
        } else {
            targets = companies.findByListUserIdOrderByName(userId);
        }
        if (request.failedOnly()) {
            targets = targets.stream().filter(c -> FAILED_STATUSES.contains(c.getLastStatus())).toList();
        }
        return targets;
    }

    private void runAll(Long runId, Long userId, List<Long> companyIds, UserSettings config, String triggeredBy) {
        limiter.setRatePerSecond(config.getRateLimitPerSec());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, config.getScanConcurrency()));
        String finalState = ScanRun.FINISHED;
        try {
            companyIds.forEach(id -> pool.execute(() -> scanner.scan(runId, id)));
            pool.shutdown();
            if (!pool.awaitTermination(12, TimeUnit.HOURS)) {
                pool.shutdownNow();
                finalState = ScanRun.INTERRUPTED;
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            finalState = ScanRun.INTERRUPTED;
        } catch (RuntimeException e) {
            pool.shutdownNow();
            finalState = ScanRun.INTERRUPTED;
            log.error("Scan run {} stopped unexpectedly", runId, e);
        } finally {
            try {
                recorder.finish(runId, finalState);
            } catch (RuntimeException e) {
                log.error("Could not mark scan run {} finished", runId, e);
            }
            scanActive.set(false);
        }
        try {
            events.publishEvent(new ScanCompleted(runId, userId, triggeredBy));
        } catch (RuntimeException e) {
            log.error("A post-scan listener failed for run {}", runId, e);
        }
    }
}
