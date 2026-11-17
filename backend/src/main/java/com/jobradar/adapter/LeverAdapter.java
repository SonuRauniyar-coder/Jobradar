package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.scan.HttpFetcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LeverAdapter implements JobSourceAdapter {
    private static final String BASE = "https://api.lever.co/v0/postings/";

    private final HttpFetcher http;

    public LeverAdapter(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public SourceType type() {
        return SourceType.LEVER;
    }

    @Override
    public boolean probe(String slug) {
        HttpFetcher.Response response = http.probeJson(BASE + slug + "?mode=json&limit=1");
        return response.ok() && !JsonSupport.parse(response.body()).isEmpty();
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        JsonNode root = JsonSupport.parse(http.getJson(BASE + target.atsSlug() + "?mode=json").requireOk().body());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : root) {
            JsonNode categories = job.path("categories");
            String hosted = JsonSupport.text(job, "hostedUrl");
            String apply = JsonSupport.text(job, "applyUrl");
            jobs.add(new RawJob(
                    JsonSupport.text(job, "id"),
                    JsonSupport.text(job, "text"),
                    firstNonNull(JsonSupport.text(categories, "department"), JsonSupport.text(categories, "team")),
                    JsonSupport.text(categories, "location"),
                    JsonSupport.text(job, "workplaceType"),
                    JsonSupport.text(categories, "commitment"),
                    description(job),
                    apply != null ? apply : hosted,
                    job.hasNonNull("createdAt") ? Instant.ofEpochMilli(job.get("createdAt").asLong()) : null,
                    hosted));
        }
        return FetchResult.complete(jobs);
    }

    private static String description(JsonNode job) {
        StringBuilder text = new StringBuilder();
        append(text, JsonSupport.text(job, "descriptionPlain"));
        for (JsonNode list : job.path("lists")) {
            append(text, JsonSupport.text(list, "text"));
            append(text, TextUtil.htmlToText(JsonSupport.text(list, "content")));
        }
        append(text, JsonSupport.text(job, "additionalPlain"));
        return text.isEmpty() ? null : text.toString().trim();
    }

    private static void append(StringBuilder out, String part) {
        if (part != null) {
            out.append(part).append("\n\n");
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
