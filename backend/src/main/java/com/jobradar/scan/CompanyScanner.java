package com.jobradar.scan;

import com.jobradar.adapter.FetchResult;
import com.jobradar.adapter.JobSourceAdapter;
import com.jobradar.adapter.ScanTarget;
import com.jobradar.company.Company;
import com.jobradar.company.CompanyRepository;
import com.jobradar.company.SourceType;
import com.jobradar.job.JobIngestService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Scans one company. Never throws: every failure becomes a recorded status, so a bulk scan cannot be stopped by it. */
@Service
public class CompanyScanner {
    private static final Logger log = LoggerFactory.getLogger(CompanyScanner.class);

    private final CompanyRepository companies;
    private final CareerDiscoveryService discovery;
    private final JobIngestService ingest;
    private final ScanRecorder recorder;
    private final Map<SourceType, JobSourceAdapter> adapters = new EnumMap<>(SourceType.class);

    public CompanyScanner(CompanyRepository companies, CareerDiscoveryService discovery, JobIngestService ingest,
                          ScanRecorder recorder, List<JobSourceAdapter> adapterList) {
        this.companies = companies;
        this.discovery = discovery;
        this.ingest = ingest;
        this.recorder = recorder;
        adapterList.forEach(a -> adapters.put(a.type(), a));
    }

    public void scan(Long runId, Long companyId) {
        long start = System.currentTimeMillis();
        ScanStatus status = ScanStatus.FAILED;
        int found = 0;
        String error = null;
        boolean redetect = false;
        Company company = null;
        try {
            company = companies.findById(companyId).orElseThrow(() -> new FetchException("Company no longer exists"));
            recorder.markCurrent(runId, company.getName());
            resolveSource(company);

            JobSourceAdapter adapter = adapters.get(company.getSourceType());
            if (adapter == null) {
                throw new FetchException(company.getSourceType() == SourceType.UNKNOWN
                        ? "Could not find a career page or ATS board. Add the career URL by hand."
                        : "No adapter for source " + company.getSourceType());
            }
            FetchResult result = adapter.fetch(new ScanTarget(company.getName(), company.getCareerUrl(), company.getAtsSlug()));
            found = ingest.ingest(companyId, result);
            status = result.partial() ? ScanStatus.PARTIAL : ScanStatus.SUCCESS;
            error = result.note();
        } catch (BlockedException e) {
            status = ScanStatus.BLOCKED;
            error = e.getMessage();
        } catch (FetchException e) {
            error = e.getMessage();
            redetect = true;
        } catch (Exception e) {
            log.warn("Scan failed for company {}", companyId, e);
            error = "Internal error: " + e.getClass().getSimpleName();
        }

        try {
            // The company may have moved ATS; let an auto-detected source be found again next time.
            if (redetect && company != null && !company.isSourceLocked() && company.getSourceType() != SourceType.UNKNOWN) {
                recorder.saveDetection(companyId, SourceType.UNKNOWN, null, company.getCareerUrl());
            }
            recorder.recordResult(runId, companyId, status, found, error, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Could not record scan result for company {}", companyId, e);
        }
    }

    private void resolveSource(Company company) {
        boolean ready = company.getSourceType() != SourceType.UNKNOWN;
        if (ready || company.isSourceLocked()) {
            return;
        }
        CareerDiscoveryService.Detected detected =
                discovery.discover(company.getName(), company.getWebsite(), company.getCareerUrl());
        company.setSourceType(detected.type());
        company.setAtsSlug(detected.slug());
        company.setCareerUrl(detected.careerUrl());
        recorder.saveDetection(company.getId(), detected.type(), detected.slug(), detected.careerUrl());
    }
}
