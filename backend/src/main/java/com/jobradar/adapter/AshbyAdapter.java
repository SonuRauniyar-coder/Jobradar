package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.company.SourceType;
import com.jobradar.scan.HttpFetcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AshbyAdapter implements JobSourceAdapter {
    private static final String BASE = "https://api.ashbyhq.com/posting-api/job-board/";

    private final HttpFetcher http;

    public AshbyAdapter(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public SourceType type() {
        return SourceType.ASHBY;
    }

    @Override
    public boolean probe(String slug) {
        HttpFetcher.Response response = http.probeJson(BASE + slug);
        return response.ok() && !JsonSupport.parse(response.body()).path("jobs").isEmpty();
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        JsonNode root = JsonSupport.parse(http.getJson(BASE + target.atsSlug()).requireOk().body());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : root.path("jobs")) {
            if (job.has("isListed") && !job.get("isListed").asBoolean()) {
                continue;
            }
            String plain = JsonSupport.text(job, "descriptionPlain");
            String jobUrl = JsonSupport.text(job, "jobUrl");
            String apply = JsonSupport.text(job, "applyUrl");
            String hint = JsonSupport.text(job, "workplaceType");
            if (hint == null && job.path("isRemote").asBoolean(false)) {
                hint = "remote";
            }
            String department = JsonSupport.text(job, "department");
            jobs.add(new RawJob(
                    JsonSupport.text(job, "id"),
                    JsonSupport.text(job, "title"),
                    department != null ? department : JsonSupport.text(job, "team"),
                    location(job),
                    hint,
                    JsonSupport.text(job, "employmentType"),
                    plain != null ? plain : TextUtil.htmlToText(JsonSupport.text(job, "descriptionHtml")),
                    apply != null ? apply : jobUrl,
                    JsonSupport.parseInstant(JsonSupport.text(job, "publishedAt")),
                    jobUrl));
        }
        return FetchResult.complete(jobs);
    }

    private static String location(JsonNode job) {
        List<String> names = new ArrayList<>();
        String primary = JsonSupport.text(job, "location");
        if (primary != null) {
            names.add(primary);
        }
        for (JsonNode secondary : job.path("secondaryLocations")) {
            String name = JsonSupport.text(secondary, "location");
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join("; ", names);
    }
}
