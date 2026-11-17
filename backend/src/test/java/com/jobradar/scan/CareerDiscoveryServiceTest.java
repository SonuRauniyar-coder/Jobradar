package com.jobradar.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.company.SourceType;
import org.junit.jupiter.api.Test;

class CareerDiscoveryServiceTest {
    private static CareerDiscoveryService.Detected detect(String html) {
        return CareerDiscoveryService.detectFromContent(html);
    }

    @Test
    void findsGreenhouseBoardLink() {
        var d = detect("<a href='https://boards.greenhouse.io/acme'>Jobs</a>");
        assertThat(d.type()).isEqualTo(SourceType.GREENHOUSE);
        assertThat(d.slug()).isEqualTo("acme");
    }

    @Test
    void findsGreenhouseEmbed() {
        var d = detect("<script src='https://boards.greenhouse.io/embed/job_board/js?for=acme'></script>");
        assertThat(d.type()).isEqualTo(SourceType.GREENHOUSE);
        assertThat(d.slug()).isEqualTo("acme");
    }

    @Test
    void findsLeverAshbySmartRecruitersWorkable() {
        assertThat(detect("https://jobs.lever.co/acme/123").type()).isEqualTo(SourceType.LEVER);
        assertThat(detect("https://jobs.ashbyhq.com/acme").type()).isEqualTo(SourceType.ASHBY);
        assertThat(detect("https://jobs.smartrecruiters.com/Acme1").type()).isEqualTo(SourceType.SMARTRECRUITERS);
        assertThat(detect("https://apply.workable.com/acme/").type()).isEqualTo(SourceType.WORKABLE);
    }

    @Test
    void findsWorkdayTenantAndSite() {
        var d = detect("href=\"https://acme.wd5.myworkdayjobs.com/en-US/External_Careers/job/1\"");
        assertThat(d.type()).isEqualTo(SourceType.WORKDAY);
        assertThat(d.slug()).isEqualTo("acme.wd5/External_Careers");
    }

    @Test
    void returnsNullWithoutAtsLinks() {
        assertThat(detect("<a href='https://example.com/careers'>Careers</a>")).isNull();
    }

    @Test
    void skipsReservedSlugs() {
        assertThat(detect("https://apply.workable.com/api/v1/x")).isNull();
    }

    @Test
    void candidatePagesPutGivenUrlFirstThenCommonPatterns() {
        assertThat(CareerDiscoveryService.candidatePages("https://www.acme.com/", "https://acme.com/work"))
                .containsExactly("https://acme.com/work", "https://www.acme.com/careers", "https://www.acme.com/jobs",
                        "https://www.acme.com/careers/jobs", "https://careers.acme.com", "https://jobs.acme.com");
    }

    @Test
    void slugGuessesUseDomainThenCleanedName() {
        assertThat(CareerDiscoveryService.candidateSlugs("Acme Labs Pvt. Ltd.", "https://www.acme.io"))
                .containsExactly("acme", "acmelabs");
        assertThat(CareerDiscoveryService.candidateSlugs("Acme Labs Inc", null))
                .containsExactly("acmelabs", "acme-labs");
    }
}
