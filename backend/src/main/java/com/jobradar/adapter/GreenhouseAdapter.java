package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.scan.HttpFetcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GreenhouseAdapter implements JobSourceAdapter {
    private static final String BASE = "https://boards-api.greenhouse.io/v1/boards/";

    private final HttpFetcher http;

    public GreenhouseAdapter(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public SourceType type() {
        return SourceType.GREENHOUSE;
    }

    @Override
    public boolean probe(String slug) {
        HttpFetcher.Response response = http.probeJson(BASE + slug + "/jobs");
        return response.ok() && !JsonSupport.parse(response.body()).path("jobs").isEmpty();
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        String url = BASE + target.atsSlug() + "/jobs?content=true";
        JsonNode root = JsonSupport.parse(http.getJson(url).requireOk().body());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : root.path("jobs")) {
            JsonNode departments = job.path("departments");
            String jobUrl = JsonSupport.text(job, "absolute_url");
            String posted = JsonSupport.text(job, "first_published");
            jobs.add(new RawJob(
                    job.path("id").asText(),
                    JsonSupport.text(job, "title"),
                    departments.isEmpty() ? null : JsonSupport.text(departments.get(0), "name"),
                    JsonSupport.text(job.path("location"), "name"),
                    null,
                    null,
                    TextUtil.htmlToText(JsonSupport.text(job, "content")),
                    jobUrl,
                    JsonSupport.parseInstant(posted != null ? posted : JsonSupport.text(job, "updated_at")),
                    jobUrl));
        }
        return FetchResult.complete(jobs);
    }
}
