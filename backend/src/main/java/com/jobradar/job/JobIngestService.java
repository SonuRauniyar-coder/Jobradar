package com.jobradar.job;

import com.jobradar.adapter.FetchResult;
import com.jobradar.adapter.RawJob;
import com.jobradar.classifier.ExperienceClassifier;
import com.jobradar.classifier.JobAttributes;
import com.jobradar.common.TextUtil;
import com.jobradar.common.UrlUtil;
import com.jobradar.company.Company;
import com.jobradar.company.CompanyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merges one fetch result into stored jobs: updates known jobs, adds new ones and closes vanished ones. */
@Service
public class JobIngestService {
    private static final int MAX_DESCRIPTION = 100_000;

    private final JobRepository jobs;
    private final CompanyRepository companies;

    private final ExperienceClassifier classifier;

    public JobIngestService(JobRepository jobs, CompanyRepository companies, ExperienceClassifier classifier) {
        this.jobs = jobs;
        this.companies = companies;
        this.classifier = classifier;
    }

    /** @return the number of distinct jobs in this fetch */
    @Transactional
    public int ingest(Long companyId, FetchResult result) {
        Company company = companies.findById(companyId).orElseThrow();
        Instant now = Instant.now();

        List<Job> existing = jobs.findByCompanyId(companyId);
        Map<String, Job> byExternalId = new HashMap<>();
        Map<String, Job> byApplyUrl = new HashMap<>();
        Map<String, Job> byHash = new HashMap<>();
        existing.forEach(j -> index(j, byExternalId, byApplyUrl, byHash));

        Map<Long, Boolean> seen = new HashMap<>();
        List<Job> created = new ArrayList<>();
        int distinct = 0;
        for (RawJob original : result.jobs()) {
            RawJob raw = clean(original);
            if (raw.title() == null || raw.title().isBlank()) {
                continue;
            }
            String hash = hash(raw);
            Job job = find(raw, hash, byExternalId, byApplyUrl, byHash);
            if (job == null) {
                job = new Job();
                job.setCompany(company);
                job.setFirstSeenAt(now);
                created.add(job);
                distinct++;
            } else if (job.getId() != null && seen.put(job.getId(), true) == null) {
                distinct++;
            }
            copy(raw, job, hash);
            job.setLastSeenAt(now);
            job.setStatusOpen(true);
            index(job, byExternalId, byApplyUrl, byHash);
        }
        jobs.saveAll(created);

        if (!result.partial()) {
            for (Job job : existing) {
                if (job.isStatusOpen() && !seen.containsKey(job.getId())) {
                    job.setStatusOpen(false);
                }
            }
        }
        long open = existing.stream().filter(Job::isStatusOpen).count() + created.size();
        company.setJobsCount((int) open);
        return distinct;
    }

    /**
     * Scraped text is untrusted: NUL characters are removed (PostgreSQL rejects them and one bad job would fail
     * the whole company), and links that are not http(s), such as javascript: or data:, are dropped.
     */
    private static RawJob clean(RawJob raw) {
        String sourceUrl = UrlUtil.isHttpUrl(raw.sourceUrl()) ? TextUtil.stripNul(raw.sourceUrl()) : null;
        String applyUrl = UrlUtil.isHttpUrl(raw.applyUrl()) ? TextUtil.stripNul(raw.applyUrl()) : sourceUrl;
        return new RawJob(TextUtil.stripNul(raw.externalId()), TextUtil.stripNul(raw.title()), TextUtil.stripNul(raw.department()),
                TextUtil.stripNul(raw.location()), TextUtil.stripNul(raw.workplaceHint()), TextUtil.stripNul(raw.employmentType()),
                TextUtil.stripNul(raw.description()), applyUrl, raw.postedAt(), sourceUrl);
    }

    private void copy(RawJob raw, Job job, String hash) {
        job.setExternalId(TextUtil.truncate(raw.externalId(), 255));
        job.setTitle(TextUtil.truncate(raw.title().trim(), 500));
        job.setDepartment(TextUtil.truncate(raw.department(), 255));
        job.setLocation(TextUtil.truncate(raw.location(), 500));
        job.setEmploymentType(TextUtil.truncate(raw.employmentType(), 64));
        job.setDescription(TextUtil.truncate(raw.description(), MAX_DESCRIPTION));
        job.setApplyUrl(TextUtil.truncate(raw.applyUrl(), 2000));
        job.setSourceUrl(TextUtil.truncate(raw.sourceUrl(), 2000));
        job.setPostedAt(raw.postedAt());
        job.setContentHash(hash);

        job.setWorkMode(JobAttributes.workMode(raw.workplaceHint(), raw.location(), raw.title(), raw.description()));
        job.setRoleCategory(JobAttributes.roleCategory(raw.title()));
        job.setSkills(TextUtil.truncate(JobAttributes.skills(raw.title(), raw.description()), 2000));

        ExperienceClassifier.Result result = classifier.classify(raw.title(), raw.description(), raw.employmentType());
        job.setExperienceRaw(TextUtil.truncate(result.experienceRaw(), 255));
        job.setMinYears(result.minYears());
        job.setMaxYears(result.maxYears());
        if (!job.isCategoryOverridden()) {
            job.setCategory(result.category());
            job.setClassificationReason(TextUtil.truncate(result.reason(), 1000));
        }
    }

    private static Job find(RawJob raw, String hash, Map<String, Job> byExternalId, Map<String, Job> byApplyUrl,
                            Map<String, Job> byHash) {
        Job job = raw.externalId() == null ? null : byExternalId.get(raw.externalId());
        if (job == null && raw.applyUrl() != null) {
            job = byApplyUrl.get(raw.applyUrl());
        }
        return job != null ? job : byHash.get(hash);
    }

    private static void index(Job job, Map<String, Job> byExternalId, Map<String, Job> byApplyUrl, Map<String, Job> byHash) {
        if (job.getExternalId() != null) {
            byExternalId.put(job.getExternalId(), job);
        }
        if (job.getApplyUrl() != null) {
            byApplyUrl.put(job.getApplyUrl(), job);
        }
        byHash.put(job.getContentHash(), job);
    }

    static String hash(RawJob raw) {
        String location = raw.location() == null ? "" : raw.location();
        return TextUtil.sha256((raw.title() + "|" + location).trim().toLowerCase(Locale.ROOT));
    }
}
