package com.jobradar.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobradar.config.JobRadarProperties;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import com.jobradar.scan.HttpFetcher.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OtherAdaptersTest {
    private final HttpFetcher http = mock(HttpFetcher.class);
    private final BrowserRenderer renderer = mock(BrowserRenderer.class);
    private final JobRadarProperties props = new JobRadarProperties(null, null, null,
            new JobRadarProperties.Scan("UA", 20, 2, 1000, 2, true, false, "", "", false), null);

    private static Response ok(String body) {
        return new Response(200, body, "https://acme.com/careers");
    }

    private static Response okAt(String body, String url) {
        return new Response(200, body, url);
    }

    private static ScanTarget slug(String s) {
        return new ScanTarget("Acme", null, s);
    }

    private static ScanTarget page(String url) {
        return new ScanTarget("Acme", url, null);
    }

    // --- Ashby

    @Test
    void ashbyMapsFieldsAndSkipsUnlisted() {
        when(http.getJson("https://api.ashbyhq.com/posting-api/job-board/acme")).thenReturn(ok("""
                {"jobs":[{"id":"1","title":"Data Engineer","department":"Data","employmentType":"FullTime",
                "location":"Pune","secondaryLocations":[{"location":"Delhi"}],"isRemote":false,"workplaceType":"Hybrid",
                "publishedAt":"2026-03-01T10:00:00.000+00:00","isListed":true,"jobUrl":"https://j/1","applyUrl":"https://j/1/apply",
                "descriptionPlain":"Own pipelines"},
                {"id":"2","title":"Hidden","isListed":false}]}
                """));
        FetchResult result = new AshbyAdapter(http).fetch(slug("acme"));
        assertThat(result.jobs()).hasSize(1);
        RawJob job = result.jobs().get(0);
        assertThat(job.location()).isEqualTo("Pune; Delhi");
        assertThat(job.workplaceHint()).isEqualTo("Hybrid");
        assertThat(job.applyUrl()).isEqualTo("https://j/1/apply");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(job.description()).isEqualTo("Own pipelines");
    }

    @Test
    void ashbyFallsBackToHtmlDescriptionAndRemoteFlag() {
        when(http.getJson("https://api.ashbyhq.com/posting-api/job-board/acme")).thenReturn(ok(
                "{\"jobs\":[{\"id\":\"1\",\"title\":\"T\",\"isRemote\":true,\"jobUrl\":\"https://j/1\",\"descriptionHtml\":\"<p>Hello</p>\"}]}"));
        RawJob job = new AshbyAdapter(http).fetch(slug("acme")).jobs().get(0);
        assertThat(job.workplaceHint()).isEqualTo("remote");
        assertThat(job.description()).isEqualTo("Hello");
        assertThat(job.applyUrl()).isEqualTo("https://j/1");
    }

    // --- SmartRecruiters

    @Test
    void smartRecruitersProbeRejectsEmptyBoardsThatReturn200() {
        when(http.probeJson(contains("/nothing/"))).thenReturn(ok("{\"totalFound\":0,\"content\":[]}"));
        when(http.probeJson(contains("/real/"))).thenReturn(ok("{\"totalFound\":3,\"content\":[{}]}"));
        SmartRecruitersAdapter adapter = new SmartRecruitersAdapter(http, props);
        assertThat(adapter.probe("nothing")).isFalse();
        assertThat(adapter.probe("real")).isTrue();
    }

    @Test
    void smartRecruitersCombinesListAndDetail() {
        when(http.getJson("https://api.smartrecruiters.com/v1/companies/acme/postings?limit=100&offset=0")).thenReturn(ok("""
                {"totalFound":1,"content":[{"id":"7","name":"Java Developer","releasedDate":"2026-02-01T00:00:00.000Z",
                "location":{"city":"Noida","region":"UP","country":"in","hybrid":true},"department":{"label":"Tech"},
                "typeOfEmployment":{"label":"Full-time"},"experienceLevel":{"label":"Entry Level"}}]}
                """));
        when(http.getJson("https://api.smartrecruiters.com/v1/companies/acme/postings/7")).thenReturn(ok("""
                {"applyUrl":"https://a/7","postingUrl":"https://p/7",
                "jobAd":{"sections":{"jobDescription":{"text":"<p>Code</p>"},"qualifications":{"text":"<p>Java</p>"}}}}
                """));
        RawJob job = new SmartRecruitersAdapter(http, props).fetch(slug("acme")).jobs().get(0);
        assertThat(job.location()).isEqualTo("Noida, UP, IN");
        assertThat(job.workplaceHint()).isEqualTo("hybrid");
        assertThat(job.applyUrl()).isEqualTo("https://a/7");
        assertThat(job.sourceUrl()).isEqualTo("https://p/7");
        assertThat(job.description()).contains("Experience level: Entry Level", "Code", "Java");
    }

    @Test
    void smartRecruitersKeepsJobWhenDetailFails() {
        when(http.getJson("https://api.smartrecruiters.com/v1/companies/acme/postings?limit=100&offset=0"))
                .thenReturn(ok("{\"totalFound\":1,\"content\":[{\"id\":\"7\",\"name\":\"Dev\",\"location\":{}}]}"));
        when(http.getJson("https://api.smartrecruiters.com/v1/companies/acme/postings/7")).thenThrow(new FetchException("down"));
        FetchResult result = new SmartRecruitersAdapter(http, props).fetch(slug("acme"));
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.partial()).isFalse();
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo("https://jobs.smartrecruiters.com/acme/7");
    }

    // --- Workable

    @Test
    void workableMapsFields() {
        when(http.getJson("https://apply.workable.com/api/v1/widget/accounts/acme?details=true")).thenReturn(ok("""
                {"name":"Acme","jobs":[{"title":"Support Engineer","shortcode":"AB12","employment_type":"Full-time",
                "telecommuting":true,"department":"CS","url":"https://apply.workable.com/j/AB12",
                "application_url":"https://apply.workable.com/j/AB12/apply","published_on":"2026-04-05",
                "country":"India","city":"Gurugram","state":"Haryana","experience":"Entry level","description":"<p>Help</p>"}]}
                """));
        RawJob job = new WorkableAdapter(http).fetch(slug("acme")).jobs().get(0);
        assertThat(job.externalId()).isEqualTo("AB12");
        assertThat(job.location()).isEqualTo("Gurugram, Haryana, India");
        assertThat(job.workplaceHint()).isEqualTo("remote");
        assertThat(job.applyUrl()).endsWith("/apply");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-04-05T00:00:00Z"));
        assertThat(job.description()).startsWith("Experience level: Entry level").contains("Help");
    }

    // --- Workday

    @Test
    void workdayCombinesListAndDetailForTenantAndSite() {
        when(http.postJson(contains("https://acme.wd5.myworkdayjobs.com/wday/cxs/acme/Ext/jobs"), anyString())).thenReturn(ok("""
                {"total":1,"jobPostings":[{"title":"SDE","externalPath":"/job/Pune/SDE_R1","locationsText":"Pune",
                "postedOn":"Posted Today","bulletFields":["R1"]}]}
                """));
        when(http.getSiteJson("https://acme.wd5.myworkdayjobs.com/wday/cxs/acme/Ext/job/Pune/SDE_R1")).thenReturn(ok("""
                {"jobPostingInfo":{"jobDescription":"<p>Build</p>","location":"Pune, India","timeType":"Full time",
                "startDate":"2026-05-06","externalUrl":"https://acme.wd5.myworkdayjobs.com/Ext/job/SDE_R1","remoteType":"Hybrid"}}
                """));
        FetchResult result = new WorkdayAdapter(http, props).fetch(slug("acme.wd5/Ext"));
        RawJob job = result.jobs().get(0);
        assertThat(result.partial()).isFalse();
        assertThat(job.externalId()).isEqualTo("R1");
        assertThat(job.location()).isEqualTo("Pune, India");
        assertThat(job.description()).isEqualTo("Build");
        assertThat(job.employmentType()).isEqualTo("Full time");
        assertThat(job.workplaceHint()).isEqualTo("Hybrid");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-05-06T00:00:00Z"));
    }

    @Test
    void workdayRejectsMalformedSlug() {
        assertThatThrownBy(() -> new WorkdayAdapter(http, props).fetch(slug("bad"))).isInstanceOf(FetchException.class);
    }

    @Test
    void workdayParsesRelativeDates() {
        WorkdayAdapter adapter = new WorkdayAdapter(http, props);
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        assertThat(adapter.parseRelative("Posted Today")).isEqualTo(today);
        assertThat(adapter.parseRelative("Posted Yesterday")).isEqualTo(today.minus(1, ChronoUnit.DAYS));
        assertThat(adapter.parseRelative("Posted 3 Days Ago")).isEqualTo(today.minus(3, ChronoUnit.DAYS));
        assertThat(adapter.parseRelative("Posted 30+ Days Ago")).isEqualTo(today.minus(30, ChronoUnit.DAYS));
    }

    // --- Generic HTML

    private GenericHtmlAdapter generic() {
        return new GenericHtmlAdapter(http, renderer, props);
    }

    private static final String LONG = "x".repeat(500);

    @Test
    void genericPrefersJsonLdOnJobPage() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok(
                "<body>" + LONG + "<a href='/jobs/1-dev'>Dev</a></body>"));
        when(http.getPage("https://acme.com/jobs/1-dev")).thenReturn(okAt("""
                <html><script type="application/ld+json">{"@type":"JobPosting","title":"Developer","identifier":{"value":"J1"},
                "description":"<p>Build things</p>","datePosted":"2026-06-07",
                "experienceRequirements":{"monthsOfExperience":24},"employmentType":["FULL_TIME"],
                "jobLocationType":"TELECOMMUTE",
                "jobLocation":{"address":{"addressLocality":"Bangalore","addressCountry":"IN"}}}</script>
                <h1>Wrong title</h1></html>
                """, "https://acme.com/jobs/1-dev"));
        FetchResult result = generic().fetch(page("https://acme.com/careers"));
        RawJob job = result.jobs().get(0);
        assertThat(result.partial()).isFalse();
        assertThat(job.title()).isEqualTo("Developer");
        assertThat(job.externalId()).isEqualTo("J1");
        assertThat(job.location()).isEqualTo("Bangalore, IN");
        assertThat(job.workplaceHint()).isEqualTo("remote");
        assertThat(job.employmentType()).isEqualTo("FULL_TIME");
        assertThat(job.description()).startsWith("Experience: 2+ years").contains("Build things");
    }

    @Test
    void genericReadsPlainHtmlJobPage() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok(
                "<body>" + LONG + "<a href='/careers/qa-analyst'>QA Analyst</a></body>"));
        when(http.getPage("https://acme.com/careers/qa-analyst")).thenReturn(okAt("""
                <body><h1>QA Analyst</h1><div class="job-location">Noida</div>
                <main><p>Responsibilities: test things</p><a href="/apply/5">Apply now</a></main></body>
                """, "https://acme.com/careers/qa-analyst"));
        RawJob job = generic().fetch(page("https://acme.com/careers")).jobs().get(0);
        assertThat(job.title()).isEqualTo("QA Analyst");
        assertThat(job.location()).isEqualTo("Noida");
        assertThat(job.applyUrl()).isEqualTo("https://acme.com/apply/5");
        assertThat(job.description()).contains("test things");
    }

    @Test
    void genericIgnoresLinkedPagesThatAreNotJobs() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok(
                "<body>" + LONG + "<a href='/careers/life-at-acme'>Life</a></body>"));
        when(http.getPage("https://acme.com/careers/life-at-acme")).thenReturn(
                okAt("<body><h1>Life at Acme</h1><main>We have snacks</main></body>", "https://acme.com/careers/life-at-acme"));
        FetchResult result = generic().fetch(page("https://acme.com/careers"));
        assertThat(result.jobs()).isEmpty();
        assertThat(result.partial()).isTrue();
    }

    @Test
    void genericFiltersNavigationAndOtherSites() {
        var links = GenericHtmlAdapter.findJobLinks(org.jsoup.Jsoup.parse("""
                <a href="/careers">Careers home</a>
                <a href="/jobs/">Jobs</a>
                <a href="/careers/open/1">Real</a>
                <a href="https://other.com/jobs/2">Elsewhere</a>
                <a href="/careers/privacy">Privacy</a>
                <a href="/products">Products</a>
                """, "https://acme.com/careers"), "https://acme.com/careers");
        assertThat(links).containsOnlyKeys("https://acme.com/careers/open/1");
    }

    @Test
    void genericRendersJavaScriptShells() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok("<body><div id=root></div></body>"));
        when(renderer.render("https://acme.com/careers")).thenReturn(Optional.of("""
                <html><script type="application/ld+json">[{"@type":"JobPosting","title":"Rendered","url":"https://acme.com/j/9"}]</script></html>
                """));
        FetchResult result = generic().fetch(page("https://acme.com/careers"));
        assertThat(result.jobs()).extracting(RawJob::title).containsExactly("Rendered");
    }

    @Test
    void genericDoesNotRenderPagesThatAlreadyHaveContent() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok("<body>" + LONG + "</body>"));
        generic().fetch(page("https://acme.com/careers"));
        verify(renderer, never()).render(anyString());
    }

    @Test
    void genericMarksResultPartialWhenPageLimitIsHit() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok("<body>" + LONG
                + "<a href='/jobs/1'>A</a><a href='/jobs/2'>B</a><a href='/jobs/3'>C</a></body>"));
        when(http.getPage(contains("/jobs/"))).thenAnswer(call -> okAt(
                "<script type='application/ld+json'>{\"@type\":\"JobPosting\",\"title\":\"T\"}</script>", call.getArgument(0)));
        FetchResult result = generic().fetch(page("https://acme.com/careers"));
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.partial()).isTrue();
    }

    @Test
    void genericSurvivesBrokenJsonLd() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok(
                "<body>" + LONG + "<script type='application/ld+json'>{broken</script></body>"));
        assertThat(generic().fetch(page("https://acme.com/careers")).jobs()).isEmpty();
    }

    @Test
    void genericKeepsPartialResultWhenOneJobPageFails() {
        when(http.getPage("https://acme.com/careers")).thenReturn(ok("<body>" + LONG
                + "<a href='/jobs/1'>A</a><a href='/jobs/2'>B</a></body>"));
        when(http.getPage("https://acme.com/jobs/1")).thenThrow(new FetchException("timeout"));
        when(http.getPage("https://acme.com/jobs/2")).thenReturn(okAt(
                "<script type='application/ld+json'>{\"@type\":\"JobPosting\",\"title\":\"T\"}</script>", "https://acme.com/jobs/2"));
        FetchResult result = generic().fetch(page("https://acme.com/careers"));
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.partial()).isTrue();
        assertThat(result.note()).contains("1 of 2");
    }
}
