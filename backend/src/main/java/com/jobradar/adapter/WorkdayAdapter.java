package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Slug format: "{tenant}.{wdN}/{site}", e.g. "nvidia.wd5/NVIDIAExternalCareerSite". */
@Component
public class WorkdayAdapter implements JobSourceAdapter {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 25;
    private static final Pattern DAYS_AGO = Pattern.compile("(\\d+)\\+? Days? Ago", Pattern.CASE_INSENSITIVE);

    private final HttpFetcher http;
    private final int maxDetailRequests;
    private final Clock clock = Clock.systemUTC();

    public WorkdayAdapter(HttpFetcher http, JobRadarProperties props) {
        this.http = http;
        this.maxDetailRequests = props.scan().maxJobPagesPerCompany();
    }

    @Override
    public SourceType type() {
        return SourceType.WORKDAY;
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        String[] parts = target.atsSlug() == null ? new String[0] : target.atsSlug().split("/", 2);
        String[] hostParts = parts.length == 2 ? parts[0].split("\\.", 2) : new String[0];
        if (hostParts.length != 2) {
            throw new FetchException("Workday slug must look like tenant.wd5/SiteName");
        }
        String tenant = hostParts[0];
        String site = parts[1];
        String origin = "https://" + tenant + "." + hostParts[1] + ".myworkdayjobs.com";
        String api = origin + "/wday/cxs/" + tenant + "/" + site;

        List<JsonNode> postings = new ArrayList<>();
        int total = -1;
        boolean exhausted = false;
        for (int page = 0; page < MAX_PAGES && !exhausted; page++) {
            String body = "{\"appliedFacets\":{},\"limit\":" + PAGE_SIZE + ",\"offset\":" + (page * PAGE_SIZE) + ",\"searchText\":\"\"}";
            JsonNode root = JsonSupport.parse(http.postJson(api + "/jobs", body).requireOk().body());
            if (total < 0) {
                total = root.path("total").asInt(); // later pages can report 0 here
            }
            JsonNode batch = root.path("jobPostings");
            batch.forEach(postings::add);
            exhausted = batch.size() < PAGE_SIZE || postings.size() >= total;
        }

        List<RawJob> jobs = new ArrayList<>();
        int details = 0;
        for (JsonNode posting : postings) {
            String path = JsonSupport.text(posting, "externalPath");
            if (path == null) {
                continue;
            }
            JsonNode detail = details++ < maxDetailRequests ? loadDetail(api + path) : null;
            jobs.add(toJob(origin, site, path, posting, detail));
        }
        return exhausted
                ? FetchResult.complete(jobs)
                : FetchResult.partial(jobs, "Only the first " + postings.size() + " of " + total + " postings were read");
    }

    private JsonNode loadDetail(String url) {
        try {
            HttpFetcher.Response response = http.getSiteJson(url);
            return response.ok() ? JsonSupport.parse(response.body()).path("jobPostingInfo") : null;
        } catch (FetchException e) {
            return null; // keep the job from the list even without its description
        }
    }

    private RawJob toJob(String origin, String site, String path, JsonNode posting, JsonNode detail) {
        String jobUrl = origin + "/en-US/" + site + path;
        JsonNode bullets = posting.path("bulletFields");
        String reqId = bullets.isEmpty() ? path : bullets.get(0).asText();
        String location = JsonSupport.text(posting, "locationsText");
        String description = null, timeType = null, hint = null, external = null;
        Instant posted = null;
        if (detail != null) {
            String detailLocation = JsonSupport.text(detail, "location");
            location = detailLocation != null ? detailLocation : location;
            description = TextUtil.htmlToText(JsonSupport.text(detail, "jobDescription"));
            timeType = JsonSupport.text(detail, "timeType");
            hint = JsonSupport.text(detail, "remoteType");
            external = JsonSupport.text(detail, "externalUrl");
            posted = JsonSupport.parseInstant(JsonSupport.text(detail, "startDate"));
        }
        if (posted == null) {
            posted = parseRelative(JsonSupport.text(posting, "postedOn"));
        }
        String url = external != null ? external : jobUrl;
        return new RawJob(reqId, JsonSupport.text(posting, "title"), null, location, hint, timeType, description, url, posted, url);
    }

    Instant parseRelative(String postedOn) {
        if (postedOn == null) {
            return null;
        }
        Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
        String lower = postedOn.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("today")) {
            return today;
        }
        if (lower.contains("yesterday")) {
            return today.minus(1, ChronoUnit.DAYS);
        }
        Matcher matcher = DAYS_AGO.matcher(postedOn);
        return matcher.find() ? today.minus(Long.parseLong(matcher.group(1)), ChronoUnit.DAYS) : null;
    }
}
