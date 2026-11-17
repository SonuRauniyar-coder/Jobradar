package com.jobradar.auth;

import com.jobradar.config.JobRadarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the single login from ADMIN_EMAIL / ADMIN_PASSWORD on first start. */
@Component
public class AdminUserInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JobRadarProperties props;

    public AdminUserInitializer(UserRepository users, PasswordEncoder encoder, JobRadarProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.admin().email();
        String password = props.admin().password();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.warn("ADMIN_EMAIL / ADMIN_PASSWORD not set; no login user was created");
            return;
        }
        if (users.findByEmailIgnoreCase(email).isEmpty()) {
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(encoder.encode(password));
            users.save(user);
            log.info("Created login user {}", email);
        }
    }
}
