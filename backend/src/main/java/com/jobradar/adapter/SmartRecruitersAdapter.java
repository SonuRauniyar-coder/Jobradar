package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SmartRecruitersAdapter implements JobSourceAdapter {
    private static final String BASE = "https://api.smartrecruiters.com/v1/companies/";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private final HttpFetcher http;
    private final int maxDetailRequests;

    public SmartRecruitersAdapter(HttpFetcher http, JobRadarProperties props) {
        this.http = http;
        this.maxDetailRequests = props.scan().maxJobPagesPerCompany();
    }

    @Override
    public SourceType type() {
        return SourceType.SMARTRECRUITERS;
    }

    @Override
    public boolean probe(String slug) {
        // This API answers 200 with totalFound=0 for slugs that do not exist.
        HttpFetcher.Response response = http.probeJson(BASE + slug + "/postings?limit=1");
        return response.ok() && JsonSupport.parse(response.body()).path("totalFound").asInt() > 0;
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        String slug = target.atsSlug();
        List<JsonNode> postings = new ArrayList<>();
        int total = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = BASE + slug + "/postings?limit=" + PAGE_SIZE + "&offset=" + (page * PAGE_SIZE);
            JsonNode root = JsonSupport.parse(http.getJson(url).requireOk().body());
            total = root.path("totalFound").asInt();
            root.path("content").forEach(postings::add);
            if (root.path("content").size() < PAGE_SIZE) {
                break;
            }
        }

        List<RawJob> jobs = new ArrayList<>();
        int details = 0;
        for (JsonNode posting : postings) {
            String id = JsonSupport.text(posting, "id");
            JsonNode detail = details++ < maxDetailRequests ? loadDetail(slug, id) : null;
            jobs.add(toJob(slug, id, posting, detail));
        }
        return postings.size() < total
                ? FetchResult.partial(jobs, "Only the first " + postings.size() + " of " + total + " postings were read")
                : FetchResult.complete(jobs);
    }

    /** The description is a bonus: a failed detail call must not lose a job already known from the list. */
    private JsonNode loadDetail(String slug, String id) {
        try {
            HttpFetcher.Response response = http.getJson(BASE + slug + "/postings/" + id);
            return response.ok() ? JsonSupport.parse(response.body()) : null;
        } catch (FetchException e) {
            return null;
        }
    }

    private static RawJob toJob(String slug, String id, JsonNode posting, JsonNode detail) {
        String hostedUrl = "https://jobs.smartrecruiters.com/" + slug + "/" + id;
        String applyUrl = detail == null ? null : JsonSupport.text(detail, "applyUrl");
        String postingUrl = detail == null ? null : JsonSupport.text(detail, "postingUrl");
        JsonNode location = posting.path("location");
        String hint = location.path("remote").asBoolean(false) ? "remote"
                : location.path("hybrid").asBoolean(false) ? "hybrid" : null;
        return new RawJob(
                id,
                JsonSupport.text(posting, "name"),
                JsonSupport.text(posting.path("department"), "label"),
                joinLocation(location),
                hint,
                JsonSupport.text(posting.path("typeOfEmployment"), "label"),
                description(posting, detail),
                applyUrl != null ? applyUrl : hostedUrl,
                JsonSupport.parseInstant(JsonSupport.text(posting, "releasedDate")),
                postingUrl != null ? postingUrl : hostedUrl);
    }

    private static String joinLocation(JsonNode location) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("city", "region", "country")) {
            String value = JsonSupport.text(location, field);
            if (value != null) {
                parts.add(field.equals("country") ? value.toUpperCase(java.util.Locale.ROOT) : value);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String description(JsonNode posting, JsonNode detail) {
        StringBuilder text = new StringBuilder();
        String level = JsonSupport.text(posting.path("experienceLevel"), "label");
        if (level != null) {
            text.append("Experience level: ").append(level).append("\n\n");
        }
        if (detail != null) {
            JsonNode sections = detail.path("jobAd").path("sections");
            for (String section : List.of("jobDescription", "qualifications", "additionalInformation")) {
                String body = TextUtil.htmlToText(JsonSupport.text(sections.path(section), "text"));
                if (body != null) {
                    text.append(body).append("\n\n");
                }
            }
        }
        return text.isEmpty() ? null : text.toString().trim();
    }
}
