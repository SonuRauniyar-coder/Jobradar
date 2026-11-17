package com.jobradar.classifier;

import static com.jobradar.job.JobEnums.Category.EXPERIENCED;
import static com.jobradar.job.JobEnums.Category.FRESHER;
import static com.jobradar.job.JobEnums.Category.UNSPECIFIED;
import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.job.JobEnums.Category;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExperienceClassifierTest {
    private final ExperienceClassifier classifier = new ExperienceClassifier();

    static Stream<Arguments> samples() {
        return Stream.of(
                // Stated years
                Arguments.of("Software Engineer", "0-2 years", FRESHER, 0, 2),
                Arguments.of("Software Engineer", "1+ years", EXPERIENCED, 1, null),
                Arguments.of("Software Engineer", "minimum 3 years", EXPERIENCED, 3, null),
                Arguments.of("Software Engineer", "2 to 5 yrs", EXPERIENCED, 2, 5),
                Arguments.of("Software Engineer", "We need 0 to 1 years of experience in Java for this long posting about our team and its mission.", FRESHER, 0, 1),
                Arguments.of("Developer", "At least 4 years of backend development experience is needed.", EXPERIENCED, 4, null),
                Arguments.of("Developer", "You have 3 years of professional experience.", EXPERIENCED, 3, null),
                Arguments.of("Developer", "Requires 5+ years of experience overall, with 2+ years of experience in Kafka.", EXPERIENCED, 5, null),
                Arguments.of("Developer 3-5 yrs", null, EXPERIENCED, 3, 5),
                Arguments.of("Developer", "Experience: 2–4 years", EXPERIENCED, 2, 4),
                // Fresher keywords in the description
                Arguments.of("Software Engineer", "Freshers can apply", FRESHER, null, null),
                Arguments.of("Software Engineer", "This is an entry level position in our team.", FRESHER, null, null),
                Arguments.of("Analyst", "Open to recent graduates from any stream", FRESHER, null, null),
                Arguments.of("Software Engineer", "Hiring from the 2025/2026 batch", FRESHER, null, null),
                Arguments.of("Developer", "No prior experience required", FRESHER, null, null),
                // Entry-level titles
                Arguments.of("Software Engineering Intern", null, FRESHER, null, null),
                Arguments.of("Graduate Trainee", null, FRESHER, null, null),
                Arguments.of("New Grad Software Engineer", null, FRESHER, null, null),
                Arguments.of("GET - Mechanical", null, FRESHER, null, null),
                Arguments.of("Junior Developer", null, FRESHER, null, null),
                Arguments.of("Campus Ambassador", null, FRESHER, null, null),
                Arguments.of("Product Manager Intern", null, FRESHER, null, null),
                // Senior titles
                Arguments.of("Senior Backend Engineer", null, EXPERIENCED, null, null),
                Arguments.of("Sr. Java Developer", null, EXPERIENCED, null, null),
                Arguments.of("Tech Lead", null, EXPERIENCED, null, null),
                Arguments.of("Principal Engineer", null, EXPERIENCED, null, null),
                Arguments.of("Staff Software Engineer", null, EXPERIENCED, null, null),
                Arguments.of("Engineering Manager", null, EXPERIENCED, null, null),
                Arguments.of("Solutions Architect", null, EXPERIENCED, null, null),
                Arguments.of("Senior Associate", null, EXPERIENCED, null, null),
                // Conflicts between signals
                Arguments.of("Senior Engineer", "Freshers can apply", EXPERIENCED, null, null),
                Arguments.of("Junior Developer", "Requires 2+ years of experience", EXPERIENCED, 2, null),
                Arguments.of("Associate Engineer", "3-5 years of experience", EXPERIENCED, 3, 5),
                // "Associate" alone says nothing about experience
                Arguments.of("Associate Software Engineer", null, UNSPECIFIED, null, null),
                Arguments.of("Associate, Cybr Sec ThreatMgmt", null, UNSPECIFIED, null, null),
                Arguments.of("Associate Consultant, Financial Institution & Counterparty Risk", null, UNSPECIFIED, null, null),
                Arguments.of("Associate Software Engineer", "Freshers can apply", FRESHER, null, null),
                // Things that must not be misread
                Arguments.of("Software Engineer", "Our company has been in business for 25 years and offers a long list of benefits to all of its employees worldwide, every year.", UNSPECIFIED, null, null),
                Arguments.of("Software Engineer", "Our founder started this journey 10 years ago and it has been a wild ride for everyone who joined along the way so far.", UNSPECIFIED, null, null),
                Arguments.of("Internal Tools Engineer", null, UNSPECIFIED, null, null),
                Arguments.of("Lead Generation Specialist", null, UNSPECIFIED, null, null),
                Arguments.of("Staffing Coordinator", null, UNSPECIFIED, null, null),
                Arguments.of("Software Engineer", "A graduate degree is a plus.", UNSPECIFIED, null, null),
                Arguments.of("Backend Engineer", "You will mentor interns and junior engineers.", UNSPECIFIED, null, null),
                Arguments.of("How to get ahead", null, UNSPECIFIED, null, null),
                Arguments.of("Software Engineer", null, UNSPECIFIED, null, null),
                Arguments.of(null, null, UNSPECIFIED, null, null));
    }

    @ParameterizedTest(name = "[{index}] {0} / {1} -> {2}")
    @MethodSource("samples")
    void classifies(String title, String description, Category category, Integer min, Integer max) {
        ExperienceClassifier.Result result = classifier.classify(title, description, null);
        assertThat(result.category()).isEqualTo(category);
        assertThat(result.minYears()).isEqualTo(min);
        assertThat(result.maxYears()).isEqualTo(max);
    }

    @Test
    void usesInternshipEmploymentType() {
        assertThat(classifier.classify("Designer", null, "Internship").category()).isEqualTo(FRESHER);
    }

    @Test
    void usesStructuredExperienceLevelFromTheSource() {
        assertThat(classifier.classify("Analyst", "Experience level: Entry Level\n\nBody", null).category()).isEqualTo(FRESHER);
        assertThat(classifier.classify("Analyst", "Experience level: Mid-Senior Level\n\nBody", null).category()).isEqualTo(EXPERIENCED);
    }

    @Test
    void reasonSaysWhy() {
        assertThat(classifier.classify("Developer", "2 to 5 yrs", null).reason()).contains("2 to 5 yrs").contains("minimum 2");
        assertThat(classifier.classify("Senior Developer", null, null).reason()).contains("Senior");
        assertThat(classifier.classify("Developer", null, null).reason()).isEqualTo("No experience information found");
    }

    @Test
    void keepsRawExperienceText() {
        assertThat(classifier.classify("Developer", "Experience: 2-4 years", null).experienceRaw()).isEqualTo("2-4 years");
    }
}
