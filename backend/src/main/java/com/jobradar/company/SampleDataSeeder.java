package com.jobradar.company;

import com.jobradar.auth.AdminUserInitializer;
import com.jobradar.auth.UserRepository;
import com.jobradar.config.JobRadarProperties;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** With SEED_SAMPLE_DATA=true, gives a user with no lists a ready-to-scan list of 20 real companies. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SampleDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);
    private static final String LIST_NAME = "Sample Companies";

    private final boolean enabled;
    private final UserRepository users;
    private final CompanyListRepository lists;
    private final CompanyService companies;
    private final CompanyImportService importer;
    private final JobRadarProperties props;

    public SampleDataSeeder(@Value("${jobradar.seed-sample-data:false}") boolean enabled, UserRepository users,
                            CompanyListRepository lists, CompanyService companies, CompanyImportService importer,
                            JobRadarProperties props, @SuppressWarnings("unused") AdminUserInitializer ensureAdminFirst) {
        this.enabled = enabled;
        this.users = users;
        this.lists = lists;
        this.companies = companies;
        this.importer = importer;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) {
            return;
        }
        var user = users.findByEmailIgnoreCase(props.admin().email()).orElse(null);
        if (user == null || !lists.findByUserIdOrderByName(user.getId()).isEmpty()) {
            return;
        }
        var list = companies.createList(user.getId(), LIST_NAME);
        try (InputStream in = new ClassPathResource("seed/sample-companies.csv").getInputStream()) {
            var result = importer.importFile(user.getId(), list.id(), "sample-companies.csv", in, true);
            log.info("Seeded {} sample companies", result.saved());
        }
    }
}
