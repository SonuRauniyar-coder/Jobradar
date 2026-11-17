package com.jobradar.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobradar.scan.BlockedException;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import com.jobradar.scan.HttpFetcher.Response;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GreenhouseLeverAdapterTest {
    private final HttpFetcher http = mock(HttpFetcher.class);
    private static final ScanTarget TARGET = new ScanTarget("Acme", null, "acme");

    private static Response ok(String body) {
        return new Response(200, body, "https://x");
    }

    @Test
    void greenhouseMapsFieldsAndUnescapesContent() {
        when(http.getJson("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true")).thenReturn(ok("""
                {"jobs":[{"id":42,"title":"Backend Engineer","absolute_url":"https://boards.greenhouse.io/acme/jobs/42",
                "first_published":"2026-01-02T03:04:05-05:00","location":{"name":"Bangalore"},
                "departments":[{"name":"Engineering"}],
                "content":"&lt;p&gt;Build &amp;amp; ship&lt;/p&gt;&lt;p&gt;2+ years&lt;/p&gt;"}]}
                """));
        RawJob job = new GreenhouseAdapter(http).fetch(TARGET).jobs().get(0);
        assertThat(job.externalId()).isEqualTo("42");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.department()).isEqualTo("Engineering");
        assertThat(job.location()).isEqualTo("Bangalore");
        assertThat(job.applyUrl()).isEqualTo("https://boards.greenhouse.io/acme/jobs/42");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-01-02T08:04:05Z"));
        assertThat(job.description()).isEqualTo("Build & ship\n\n2+ years");
    }

    @Test
    void greenhouseProbeNeedsAtLeastOneJob() {
        when(http.probeJson("https://boards-api.greenhouse.io/v1/boards/empty/jobs")).thenReturn(ok("{\"jobs\":[]}"));
        when(http.probeJson("https://boards-api.greenhouse.io/v1/boards/full/jobs")).thenReturn(ok("{\"jobs\":[{\"id\":1}]}"));
        when(http.probeJson("https://boards-api.greenhouse.io/v1/boards/none/jobs")).thenReturn(new Response(404, "", "u"));
        GreenhouseAdapter adapter = new GreenhouseAdapter(http);
        assertThat(adapter.probe("empty")).isFalse();
        assertThat(adapter.probe("full")).isTrue();
        assertThat(adapter.probe("none")).isFalse();
    }

    @Test
    void greenhouseErrorStatusFailsTheFetch() {
        when(http.getJson("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true"))
                .thenReturn(new Response(404, "", "https://x"));
        assertThatThrownBy(() -> new GreenhouseAdapter(http).fetch(TARGET)).isInstanceOf(FetchException.class);
    }

    @Test
    void forbiddenIsReportedAsBlocked() {
        when(http.getJson("https://api.lever.co/v0/postings/acme?mode=json")).thenReturn(new Response(403, "", "https://x"));
        assertThatThrownBy(() -> new LeverAdapter(http).fetch(TARGET)).isInstanceOf(BlockedException.class);
    }

    @Test
    void malformedJsonFailsTheFetch() {
        when(http.getJson("https://api.lever.co/v0/postings/acme?mode=json")).thenReturn(ok("<html>"));
        assertThatThrownBy(() -> new LeverAdapter(http).fetch(TARGET)).isInstanceOf(FetchException.class);
    }

    @Test
    void leverMapsFieldsAndJoinsDescriptionSections() {
        when(http.getJson("https://api.lever.co/v0/postings/acme?mode=json")).thenReturn(ok("""
                [{"id":"abc","text":"QA Intern","hostedUrl":"https://jobs.lever.co/acme/abc",
                "applyUrl":"https://jobs.lever.co/acme/abc/apply","createdAt":1767225600000,
                "workplaceType":"hybrid","descriptionPlain":"About the role",
                "lists":[{"text":"Requirements","content":"<li>Curious</li>"}],"additionalPlain":"Benefits",
                "categories":{"commitment":"Internship","team":"QA","location":"Noida"}}]
                """));
        RawJob job = new LeverAdapter(http).fetch(TARGET).jobs().get(0);
        assertThat(job.externalId()).isEqualTo("abc");
        assertThat(job.department()).isEqualTo("QA");
        assertThat(job.location()).isEqualTo("Noida");
        assertThat(job.employmentType()).isEqualTo("Internship");
        assertThat(job.workplaceHint()).isEqualTo("hybrid");
        assertThat(job.applyUrl()).endsWith("/apply");
        assertThat(job.sourceUrl()).isEqualTo("https://jobs.lever.co/acme/abc");
        assertThat(job.postedAt()).isEqualTo(Instant.ofEpochMilli(1767225600000L));
        assertThat(job.description()).contains("About the role", "Requirements", "Curious", "Benefits");
    }

    @Test
    void leverEmptyBoardIsACompleteEmptyResult() {
        when(http.getJson("https://api.lever.co/v0/postings/acme?mode=json")).thenReturn(ok("[]"));
        FetchResult result = new LeverAdapter(http).fetch(TARGET);
        assertThat(result.jobs()).isEmpty();
        assertThat(result.partial()).isFalse();
    }
}
