package com.jobradar.scan;

import com.jobradar.common.TextUtil;
import com.jobradar.company.CompanyRepository;
import com.jobradar.company.SourceType;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions for scan bookkeeping, so no database connection is held during network calls. */
@Service
public class ScanRecorder {
    private final ScanRunRepository runs;
    private final ScanLogRepository logs;
    private final CompanyRepository companies;

    public ScanRecorder(ScanRunRepository runs, ScanLogRepository logs, CompanyRepository companies) {
        this.runs = runs;
        this.logs = logs;
        this.companies = companies;
    }

    @Transactional
    public Long createRun(Long userId, int total, String triggeredBy) {
        ScanRun run = new ScanRun();
        run.setUserId(userId);
        run.setTotal(total);
        run.setTriggeredBy(triggeredBy);
        return runs.save(run).getId();
    }

    @Transactional
    public void markCurrent(Long runId, String companyName) {
        runs.setCurrent(runId, companyName);
    }

    @Transactional
    public void saveDetection(Long companyId, SourceType type, String slug, String careerUrl) {
        companies.findById(companyId).ifPresent(company -> {
            company.setSourceType(type);
            company.setAtsSlug(slug);
            company.setCareerUrl(TextUtil.truncate(careerUrl, 1000));
        });
    }

    @Transactional
    public void recordResult(Long runId, Long companyId, ScanStatus status, int jobsFound, String error, long durationMs) {
        String shortError = TextUtil.truncate(error, 2000);
        boolean companyExists = companies.findById(companyId).map(company -> {
            company.setLastScannedAt(Instant.now());
            company.setLastStatus(status.name());
            company.setLastError(shortError);
            return true;
        }).orElse(false);

        if (companyExists) {
            ScanLog log = new ScanLog();
            log.setScanRunId(runId);
            log.setCompanyId(companyId);
            log.setStatus(status.name());
            log.setJobsFound(jobsFound);
            log.setError(shortError);
            log.setDurationMs(durationMs);
            logs.save(log);
        }
        boolean succeeded = status == ScanStatus.SUCCESS || status == ScanStatus.PARTIAL;
        runs.addResult(runId, succeeded ? 1 : 0, succeeded ? 0 : 1);
    }

    @Transactional
    public void finish(Long runId, String state) {
        runs.finish(runId, state, Instant.now());
    }

    @Transactional
    public int interruptStaleRuns() {
        return runs.interruptAllRunning(Instant.now());
    }
}
