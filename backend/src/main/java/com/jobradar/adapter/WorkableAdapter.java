package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.scan.HttpFetcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WorkableAdapter implements JobSourceAdapter {
    private static final String BASE = "https://apply.workable.com/api/v1/widget/accounts/";

    private final HttpFetcher http;

    public WorkableAdapter(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public SourceType type() {
        return SourceType.WORKABLE;
    }

    @Override
    public boolean probe(String slug) {
        HttpFetcher.Response response = http.probeJson(BASE + slug);
        return response.ok() && !JsonSupport.parse(response.body()).path("jobs").isEmpty();
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        JsonNode root = JsonSupport.parse(http.getJson(BASE + target.atsSlug() + "?details=true").requireOk().body());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : root.path("jobs")) {
            String url = JsonSupport.text(job, "url");
            String apply = JsonSupport.text(job, "application_url");
            String shortcode = JsonSupport.text(job, "shortcode");
            jobs.add(new RawJob(
                    shortcode != null ? shortcode : JsonSupport.text(job, "code"),
                    JsonSupport.text(job, "title"),
                    JsonSupport.text(job, "department"),
                    location(job),
                    job.path("telecommuting").asBoolean(false) ? "remote" : null,
                    JsonSupport.text(job, "employment_type"),
                    description(job),
                    apply != null ? apply : url,
                    JsonSupport.parseInstant(firstNonNull(JsonSupport.text(job, "published_on"), JsonSupport.text(job, "created_at"))),
                    url != null ? url : JsonSupport.text(job, "shortlink")));
        }
        return FetchResult.complete(jobs);
    }

    private static String location(JsonNode job) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("city", "state", "country")) {
            String value = JsonSupport.text(job, field);
            if (value != null) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String description(JsonNode job) {
        String experience = JsonSupport.text(job, "experience");
        String body = TextUtil.htmlToText(JsonSupport.text(job, "description"));
        if (experience == null) {
            return body;
        }
        return "Experience level: " + experience + (body == null ? "" : "\n\n" + body);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
