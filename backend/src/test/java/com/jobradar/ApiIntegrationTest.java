package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.auth.JwtService;
import com.jobradar.auth.User;
import com.jobradar.auth.UserRepository;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import com.jobradar.scan.HttpFetcher.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real API and migrations against PostgreSQL. Only outbound HTTP to job boards is faked. */
@SpringBootTest(properties = {
        "jobradar.admin.email=admin@test.local",
        "jobradar.admin.password=password123",
        "jobradar.jwt.secret=integration-test-secret-integration-test-secret",
        "jobradar.scan.schedule-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ApiIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @MockitoBean HttpFetcher http;

    private String token;

    private static final String THREE_JOBS = """
            {"jobs":[
              {"id":1,"title":"Software Engineering Intern","absolute_url":"https://b/1","location":{"name":"Noida, India"},"content":"Learn Java"},
              {"id":2,"title":"Senior Backend Engineer","absolute_url":"https://b/2","location":{"name":"Remote - US"},"content":"Kubernetes"},
              {"id":3,"title":"Software Engineer","absolute_url":"https://b/3","location":{"name":"Indianapolis, IN"},"content":"Build things"}
            ]}
            """;
    private static final String ONE_JOB = """
            {"jobs":[{"id":1,"title":"Software Engineering Intern","absolute_url":"https://b/1","location":{"name":"Noida, India"},"content":"Learn Java"}]}
            """;

    @BeforeEach
    void login() throws Exception {
        token = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@test.local\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode body(String json) throws Exception {
        return mapper.readTree(json);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode getJson(String url) throws Exception {
        return body(mvc.perform(auth(get(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String json, int expected) throws Exception {
        String response = mvc.perform(auth(request).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isEmpty() ? null : body(response);
    }

    private long newList() throws Exception {
        return send(post("/api/lists"), "{\"name\":\"List " + UUID.randomUUID() + "\"}", 201).get("id").asLong();
    }

    private JsonNode upload(long listId, String csv, boolean commit) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "c.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return body(mvc.perform(auth(multipart("/api/lists/" + listId + "/upload?commit=" + commit).file(file)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode scan(long listId) throws Exception {
        long id = send(post("/api/scans"), "{\"listId\":" + listId + "}", 202).get("id").asLong();
        await().atMost(Duration.ofSeconds(20)).until(() -> !getJson("/api/scans/" + id).get("state").asText().equals("RUNNING"));
        return getJson("/api/scans/" + id);
    }

    private long total(String query) throws Exception {
        return getJson("/api/jobs?" + query).get("totalElements").asLong();
    }

    private JsonNode companyByName(long listId, String name) throws Exception {
        for (JsonNode company : getJson("/api/companies?listId=" + listId)) {
            if (company.get("name").asText().equals(name)) {
                return company;
            }
        }
        throw new AssertionError("No company " + name);
    }

    private String uniqueSlug() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void board(String slug, String json) {
        when(http.getJson(contains("/boards/" + slug + "/jobs"))).thenReturn(new Response(200, json, "https://b"));
    }

    private JsonNode jobNamed(String query, String title) throws Exception {
        for (JsonNode job : getJson("/api/jobs?size=200&" + query).get("content")) {
            if (job.get("title").asText().equals(title)) {
                return job;
            }
        }
        throw new AssertionError("No job " + title);
    }

    @Test
    void authProtectsTheApi() throws Exception {
        mvc.perform(get("/api/lists")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/lists").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@test.local\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadPreviewsThenCommitsAndSkipsDuplicates() throws Exception {
        long listId = newList();
        String csv = "company_name,website\nAcme,https://acme.com\nACME,https://x.com\nOther,https://www.acme.com\n,https://y.com\nGood,https://good.com\n";

        JsonNode preview = upload(listId, csv, false);
        assertThat(preview.get("valid").asInt()).isEqualTo(2);
        assertThat(preview.get("duplicates").asInt()).isEqualTo(2);
        assertThat(preview.get("errors").asInt()).isEqualTo(1);
        assertThat(getJson("/api/companies?listId=" + listId)).isEmpty();

        assertThat(upload(listId, csv, true).get("saved").asInt()).isEqualTo(2);
        assertThat(getJson("/api/companies?listId=" + listId)).hasSize(2);
        assertThat(upload(listId, csv, true).get("saved").asInt()).isZero();
    }

    @Test
    void uploadEnforcesThe500CompanyLimit() throws Exception {
        long listId = newList();
        StringBuilder csv = new StringBuilder("company_name\n");
        for (int i = 0; i < 503; i++) {
            csv.append("Company ").append(i).append('\n');
        }
        JsonNode result = upload(listId, csv.toString(), true);
        assertThat(result.get("saved").asInt()).isEqualTo(500);
        assertThat(result.get("errors").asInt()).isEqualTo(3);
        send(post("/api/companies"), "{\"listId\":" + listId + ",\"name\":\"One too many\"}", 400);
    }

    @Test
    void companyCrudAndManualSourceCorrection() throws Exception {
        long listId = newList();
        JsonNode created = send(post("/api/companies"), "{\"listId\":" + listId + ",\"name\":\"Acme\",\"website\":\"acme.com\"}", 201);
        long id = created.get("id").asLong();
        assertThat(created.get("website").asText()).isEqualTo("https://acme.com");

        send(post("/api/companies"), "{\"listId\":" + listId + ",\"name\":\"acme\"}", 409);
        send(post("/api/companies"), "{\"listId\":" + listId + ",\"name\":\"Bad\",\"website\":\"not a url\"}", 400);

        JsonNode updated = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/companies/" + id),
                "{\"name\":\"Acme\",\"sourceType\":\"LEVER\",\"atsSlug\":\"acme\"}", 200);
        assertThat(updated.get("sourceType").asText()).isEqualTo("LEVER");
        assertThat(updated.get("sourceLocked").asBoolean()).isTrue();

        mvc.perform(auth(delete("/api/companies/" + id))).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/companies/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void scanClassifiesFiltersAndTracksApplications() throws Exception {
        String slug = uniqueSlug();
        board(slug, THREE_JOBS);
        long listId = newList();
        String company = "Co " + slug;
        upload(listId, "company_name,career_page_url\n" + company + ",https://boards.greenhouse.io/" + slug + "\n", true);

        JsonNode run = scan(listId);
        assertThat(run.get("state").asText()).isEqualTo("FINISHED");
        assertThat(run.get("success").asInt()).isEqualTo(1);
        assertThat(companyByName(listId, company).get("sourceType").asText()).isEqualTo("GREENHOUSE");
        assertThat(companyByName(listId, company).get("jobsCount").asInt()).isEqualTo(3);

        String scope = "company=" + slug + "&";
        // Unclassified jobs show under both the Fresher and Experienced tabs.
        assertThat(total(scope + "category=FRESHER")).isEqualTo(2);
        assertThat(total(scope + "category=EXPERIENCED")).isEqualTo(2);
        assertThat(total(scope + "category=UNSPECIFIED")).isEqualTo(1);
        assertThat(total(scope.substring(0, scope.length() - 1))).isEqualTo(3);
        // "Indianapolis, IN" must not count as India.
        assertThat(total(scope + "indiaOnly=true")).isEqualTo(1);
        assertThat(total(scope + "location=noida")).isEqualTo(1);
        assertThat(total(scope + "q=kubernetes")).isEqualTo(1);
        assertThat(total(scope + "workMode=REMOTE")).isEqualTo(1);
        assertThat(total(scope + "roleCategory=BACKEND")).isEqualTo(1);

        JsonNode intern = jobNamed(scope, "Software Engineering Intern");
        assertThat(intern.get("isNew").asBoolean()).isTrue();
        long internId = intern.get("id").asLong();
        assertThat(getJson("/api/jobs/" + internId).get("classificationReason").asText()).contains("Intern");

        JsonNode applied = send(patch("/api/jobs/" + internId + "/status"),
                "{\"status\":\"APPLIED\",\"notes\":\"sent\",\"followUpDate\":\"2030-01-02\"}", 200);
        assertThat(applied.get("job").get("appliedAt").isNull()).isFalse();
        assertThat(applied.get("notes").asText()).isEqualTo("sent");
        assertThat(total(scope + "status=APPLIED")).isEqualTo(1);
        assertThat(total(scope + "status=NEW")).isEqualTo(2);
        assertThat(getJson("/api/tracker/board").findValuesAsText("title")).contains("Software Engineering Intern");

        long seniorId = jobNamed(scope, "Senior Backend Engineer").get("id").asLong();
        send(patch("/api/jobs/" + seniorId + "/status"), "{\"status\":\"IGNORED\"}", 200);
        assertThat(total(scope.substring(0, scope.length() - 1))).isEqualTo(2);
        assertThat(total(scope + "status=IGNORED")).isEqualTo(1);

        String csv = mvc.perform(auth(get("/api/jobs/export?format=csv&" + scope + "indiaOnly=true")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Software Engineering Intern").doesNotContain("Senior Backend Engineer");
        byte[] xlsx = mvc.perform(auth(get("/api/jobs/export?format=xlsx&" + scope + "indiaOnly=true")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx).startsWith((byte) 'P', (byte) 'K');

        JsonNode stats = getJson("/api/stats/dashboard");
        assertThat(stats.get("openJobs").asLong()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rescanClosesVanishedJobsAndKeepsUserChoices() throws Exception {
        String slug = uniqueSlug();
        board(slug, THREE_JOBS);
        long listId = newList();
        String company = "Co " + slug;
        upload(listId, "company_name,career_page_url\n" + company + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);

        String scope = "company=" + slug;
        long id = jobNamed(scope, "Software Engineering Intern").get("id").asLong();
        send(patch("/api/jobs/" + id + "/category"), "{\"category\":\"EXPERIENCED\"}", 200);
        send(patch("/api/jobs/" + id + "/status"), "{\"status\":\"SAVED\"}", 200);

        board(slug, ONE_JOB);
        scan(listId);

        assertThat(total(scope)).isEqualTo(1);
        assertThat(companyByName(listId, company).get("jobsCount").asInt()).isEqualTo(1);
        JsonNode job = getJson("/api/jobs/" + id);
        assertThat(job.get("job").get("id").asLong()).isEqualTo(id);
        assertThat(job.get("job").get("category").asText()).isEqualTo("EXPERIENCED");
        assertThat(job.get("job").get("status").asText()).isEqualTo("SAVED");

        board(slug, THREE_JOBS);
        scan(listId);
        assertThat(total(scope)).isEqualTo(3); // closed jobs come back when they reappear
    }

    @Test
    void oneFailingCompanyDoesNotStopTheScanAndCanBeRescannedAlone() throws Exception {
        String good = uniqueSlug();
        String bad = uniqueSlug();
        board(good, ONE_JOB);
        when(http.getJson(contains("/boards/" + bad + "/jobs"))).thenThrow(new FetchException("boom"));
        long listId = newList();
        upload(listId, "company_name,career_page_url\nGood " + good + ",https://boards.greenhouse.io/" + good
                + "\nBad " + bad + ",https://boards.greenhouse.io/" + bad + "\n", true);

        JsonNode run = scan(listId);
        assertThat(run.get("total").asInt()).isEqualTo(2);
        assertThat(run.get("success").asInt()).isEqualTo(1);
        assertThat(run.get("failed").asInt()).isEqualTo(1);
        JsonNode failed = companyByName(listId, "Bad " + bad);
        assertThat(failed.get("lastStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.get("lastError").asText()).contains("boom");

        long rerun = send(post("/api/scans"), "{\"listId\":" + listId + ",\"failedOnly\":true}", 202).get("id").asLong();
        assertThat(getJson("/api/scans/" + rerun).get("total").asInt()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).until(() -> !getJson("/api/scans/" + rerun).get("state").asText().equals("RUNNING"));
    }

    @Test
    void savedSearchCrud() throws Exception {
        JsonNode created = send(post("/api/saved-searches"),
                "{\"name\":\"India QA\",\"filters\":{\"indiaOnly\":true,\"roleCategory\":\"QA_TESTING\"},\"emailAlert\":true}", 201);
        long id = created.get("id").asLong();
        assertThat(created.get("filters").get("roleCategory").asText()).isEqualTo("QA_TESTING");
        assertThat(getJson("/api/saved-searches").findValuesAsText("name")).contains("India QA");
        mvc.perform(auth(delete("/api/saved-searches/" + id))).andExpect(status().isNoContent());
    }

    @Test
    void oneUserCannotSeeAnothersData() throws Exception {
        String slug = uniqueSlug();
        board(slug, ONE_JOB);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        long jobId = jobNamed("company=" + slug, "Software Engineering Intern").get("id").asLong();
        long companyId = getJson("/api/companies?listId=" + listId).get(0).get("id").asLong();

        User other = new User();
        other.setEmail(UUID.randomUUID() + "@other.local");
        other.setPasswordHash("x");
        users.save(other);
        token = jwt.issue(other.getId());

        mvc.perform(auth(get("/api/jobs/" + jobId))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/companies/" + companyId))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/companies?listId=" + listId))).andExpect(status().isNotFound());
        assertThat(total("company=" + slug)).isZero();
        send(patch("/api/jobs/" + jobId + "/status"), "{\"status\":\"SAVED\"}", 404);
    }

    @Test
    void nullPostedDatesSortLast() throws Exception {
        String slug = uniqueSlug();
        board(slug, """
                {"jobs":[{"id":1,"title":"No Date","absolute_url":"https://b/1"},
                {"id":2,"title":"Has Date","absolute_url":"https://b/2","first_published":"2026-01-01T00:00:00Z"}]}
                """);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        assertThat(getJson("/api/jobs?company=" + slug).get("content").get(0).get("title").asText()).isEqualTo("Has Date");
    }

    @Test
    void invalidInputGivesClientErrors() throws Exception {
        when(http.getPage(anyString())).thenThrow(new FetchException("unused"));
        mvc.perform(auth(get("/api/jobs?sort=description,asc"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/jobs?category=NOPE"))).andExpect(status().isBadRequest());
        send(post("/api/scans"), "{\"listId\":999999}", 400);
        mvc.perform(auth(get("/api/scans/999999"))).andExpect(status().isNotFound());
    }

    // ---- Regression tests for the QA report of 10 Nov 2026 ----

    @Test
    void badBodiesAndParametersAreClientErrorsNot500() throws Exception {
        send(post("/api/lists"), "{bad json", 400);
        send(patch("/api/jobs/1/status"), "{\"status\":\"NOPE\"}", 400);
        send(put("/api/settings"), "{\"profileKeywords\":[],\"preferredLocations\":[],\"scanConcurrency\":\"x\",\"rateLimitPerSec\":1}", 400);
        mvc.perform(auth(get("/api/jobs/abc"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/companies/99999999999999999999"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/jobs?page=abc"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/jobs?page=2147483647"))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/jobs/export?format=pdf"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/jobs?sort=title,sideways"))).andExpect(status().isBadRequest());
    }

    @Test
    void nulCharactersAreStrippedInsteadOfFailing() throws Exception {
        JsonNode list = send(post("/api/lists"), "{\"name\":\"Nul\\u0000List " + UUID.randomUUID() + "\"}", 201);
        assertThat(list.get("name").asText()).startsWith("NulList ");
        send(put("/api/settings"),
                "{\"profileKeywords\":[null,\"java\"],\"preferredLocations\":[],\"scanConcurrency\":10,\"rateLimitPerSec\":0.25}", 200);
    }

    @Test
    void uploadPreviewFlagsOverlongFieldsAndSaveKeepsTheGoodRows() throws Exception {
        long listId = newList();
        String csv = "company_name,career_page_url,location_preference\n"
                + "Good Co,https://good.example.com/careers,Noida\n"
                + "Long Location,https://long.example.com," + "x".repeat(256) + "\n"
                + "Long Url,https://long2.example.com/" + "a".repeat(1000) + ",\n";
        JsonNode preview = upload(listId, csv, false);
        assertThat(preview.get("valid").asInt()).isEqualTo(1);
        assertThat(preview.get("errors").asInt()).isEqualTo(2);
        assertThat(upload(listId, csv, true).get("saved").asInt()).isEqualTo(1);
    }

    @Test
    void parallelUploadCommitsDoNotCreateDuplicates() throws Exception {
        long listId = newList();
        String csv = "company_name\nRace Co\n";
        runInParallel(4, () -> upload(listId, csv, true));
        assertThat(getJson("/api/companies?listId=" + listId)).hasSize(1);
    }

    @Test
    void parallelCreatesOfOneListNameGiveConflictsNot500() throws Exception {
        String name = "{\"name\":\"Same " + UUID.randomUUID() + "\"}";
        List<Integer> codes = runInParallel(4, () -> mvc.perform(auth(post("/api/lists"))
                .contentType(MediaType.APPLICATION_JSON).content(name)).andReturn().getResponse().getStatus());
        assertThat(codes).containsOnly(201, 409).containsOnlyOnce(201);
    }

    @Test
    void parallelFirstStatusWritesAllSucceed() throws Exception {
        String slug = uniqueSlug();
        board(slug, ONE_JOB);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        long jobId = jobNamed("company=" + slug, "Software Engineering Intern").get("id").asLong();
        List<Integer> codes = runInParallel(4, () -> mvc.perform(auth(patch("/api/jobs/" + jobId + "/status"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SAVED\"}")).andReturn().getResponse().getStatus());
        assertThat(codes).containsOnly(200);
    }

    @Test
    void notesSavedFromAnOutdatedPageAreRejected() throws Exception {
        String slug = uniqueSlug();
        board(slug, ONE_JOB);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        long jobId = jobNamed("company=" + slug, "Software Engineering Intern").get("id").asLong();
        send(patch("/api/jobs/" + jobId + "/status"), "{\"notes\":\"from tab 1\",\"expectedNotes\":\"\"}", 200);
        send(patch("/api/jobs/" + jobId + "/status"), "{\"notes\":\"from tab 2\",\"expectedNotes\":\"\"}", 409);
        assertThat(getJson("/api/jobs/" + jobId).get("notes").asText()).isEqualTo("from tab 1");
    }

    @Test
    void editingADetectedCompanyKeepsItsSource() throws Exception {
        String slug = uniqueSlug();
        board(slug, ONE_JOB);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        JsonNode company = companyByName(listId, "Co " + slug);
        assertThat(company.get("sourceType").asText()).isEqualTo("GREENHOUSE");
        JsonNode updated = send(put("/api/companies/" + company.get("id").asLong()),
                "{\"listId\":" + listId + ",\"name\":\"Co " + slug + "\",\"careerUrl\":\"" + company.get("careerUrl").asText()
                        + "\",\"locationPreference\":\"Pune\",\"sourceType\":null}", 200);
        assertThat(updated.get("sourceType").asText()).isEqualTo("GREENHOUSE");
        assertThat(updated.get("atsSlug").asText()).isEqualTo(company.get("atsSlug").asText());
    }

    @Test
    void movingACompanyToAnotherUsersListIsRefused() throws Exception {
        long listId = newList();
        upload(listId, "company_name\nMover Co\n", true);
        long companyId = companyByName(listId, "Mover Co").get("id").asLong();
        send(put("/api/companies/" + companyId), "{\"listId\":999999,\"name\":\"Mover Co\"}", 404);
    }

    @Test
    void scrapedJavascriptLinksAreNotStored() throws Exception {
        String slug = uniqueSlug();
        board(slug, """
                {"jobs":[{"id":1,"title":"Script Link Job","absolute_url":"javascript:alert(document.domain)"}]}
                """);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        JsonNode job = jobNamed("company=" + slug, "Script Link Job");
        assertThat(job.get("applyUrl").isNull() || job.get("applyUrl").asText().startsWith("http")).isTrue();
    }

    @Test
    void dashboardCountsMatchTheJobTabs() throws Exception {
        String slug = uniqueSlug();
        board(slug, THREE_JOBS);
        long listId = newList();
        upload(listId, "company_name,career_page_url\nCo " + slug + ",https://boards.greenhouse.io/" + slug + "\n", true);
        scan(listId);
        long ignored = jobNamed("company=" + slug, "Software Engineer").get("id").asLong();
        send(patch("/api/jobs/" + ignored + "/status"), "{\"status\":\"IGNORED\"}", 200);
        JsonNode stats = getJson("/api/stats/dashboard");
        assertThat(stats.get("openJobs").asLong()).isEqualTo(total("size=1"));
        assertThat(stats.get("fresherJobs").asLong()).isEqualTo(total("category=FRESHER"));
        assertThat(stats.get("experiencedJobs").asLong()).isEqualTo(total("category=EXPERIENCED"));
    }

    @Test
    void exportSaysHowManyJobsMatched() throws Exception {
        mvc.perform(auth(get("/api/jobs/export?format=csv")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Total-Count"));
    }

    @Test
    void repeatedFailedLoginsAreThrottled() throws Exception {
        String login = "{\"email\":\"" + UUID.randomUUID() + "@nobody.local\",\"password\":\"wrong\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void tokenForADeletedUserIsRejected() throws Exception {
        User gone = new User();
        gone.setEmail(UUID.randomUUID() + "@gone.local");
        gone.setPasswordHash("x");
        users.save(gone);
        token = jwt.issue(gone.getId());
        users.delete(gone);
        mvc.perform(auth(get("/api/lists"))).andExpect(status().isUnauthorized());
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private static <T> List<T> runInParallel(int count, Call<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.run();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
