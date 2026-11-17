package com.jobradar.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.job.JobEnums.RoleCategory;
import com.jobradar.job.JobEnums.WorkMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobAttributesTest {
    @ParameterizedTest
    @CsvSource({
            "Senior Backend Engineer,BACKEND", "Java Developer,BACKEND", "Frontend Engineer,FRONTEND", "React Developer,FRONTEND",
            "Full Stack Developer,FULL_STACK", "Full-Stack Engineer,FULL_STACK", "SDET,QA_TESTING", "QA Analyst,QA_TESTING",
            "Test Automation Engineer,QA_TESTING", "DevOps Engineer,DEVOPS", "Site Reliability Engineer,DEVOPS",
            "Network Engineer,NETWORK", "Data Scientist,DATA", "Machine Learning Engineer,DATA", "Account Executive,OTHER",
            "Software Engineer,OTHER"})
    void derivesRoleCategory(String title, RoleCategory expected) {
        assertThat(JobAttributes.roleCategory(title)).isEqualTo(expected);
    }

    @Test
    void workModePrefersTheExplicitHint() {
        assertThat(JobAttributes.workMode("Hybrid", "Remote - India", null, null)).isEqualTo(WorkMode.HYBRID);
        assertThat(JobAttributes.workMode("on-site", null, null, null)).isEqualTo(WorkMode.ONSITE);
    }

    @Test
    void workModeReadsLocationAndTitle() {
        assertThat(JobAttributes.workMode(null, "Remote - India", "Dev", null)).isEqualTo(WorkMode.REMOTE);
        assertThat(JobAttributes.workMode(null, "Pune", "Dev (Hybrid)", null)).isEqualTo(WorkMode.HYBRID);
    }

    @Test
    void workModeIgnoresPassingMentionsOfRemote() {
        assertThat(JobAttributes.workMode(null, "Pune", "Dev", "Collaborate with remote teams")).isEqualTo(WorkMode.UNKNOWN);
        assertThat(JobAttributes.workMode(null, "Pune", "Dev", "This is a fully remote role")).isEqualTo(WorkMode.REMOTE);
    }

    @Test
    void extractsSkillsIncludingSymbolNames() {
        assertThat(JobAttributes.skills("C++ Developer", "Uses C#, .NET and Node.js with spring boot").split(", "))
                .contains("C++", "C#", ".NET", "Node.js", "Spring Boot");
    }

    @Test
    void doesNotFindSkillsInsideOtherWordsOrOrdinaryEnglish() {
        String skills = JobAttributes.skills("Dev", "We go far, trust our gut, and rest well. JavaScript-free javanese.");
        assertThat(skills.split(", ")).doesNotContain("Go", "Rust", "REST", "Java");
    }

    @Test
    void matchScoreIsThePercentOfKeywordsFound() {
        List<String> keywords = List.of("Java", "Spring Boot", "React", "QA");
        List<String> matched = JobAttributes.matchedKeywords(keywords, "Java Developer", "Spring Boot", "javascript only");
        assertThat(matched).containsExactly("Java", "Spring Boot");
        assertThat(JobAttributes.matchScore(keywords, matched)).isEqualTo(50);
        assertThat(JobAttributes.matchScore(List.of(), List.of())).isZero();
    }
}
