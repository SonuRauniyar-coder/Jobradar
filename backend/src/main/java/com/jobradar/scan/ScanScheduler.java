package com.jobradar.scan;

import com.jobradar.auth.User;
import com.jobradar.auth.UserRepository;
import com.jobradar.common.ApiException;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.notification.DigestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScanScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final ScanService scans;
    private final UserRepository users;
    private final boolean enabled;

    public ScanScheduler(ScanService scans, UserRepository users, JobRadarProperties props) {
        this.scans = scans;
        this.users = users;
        this.enabled = props.scan().scheduleEnabled();
    }

    @Scheduled(cron = "${jobradar.scan.cron}", zone = "${jobradar.scan.zone}")
    public void dailyScan() {
        if (!enabled) {
            return;
        }
        for (User user : users.findAll()) {
            try {
                scans.start(user.getId(), new ScanService.ScanRequest(null, null, false), DigestService.SCHEDULED);
            } catch (ApiException e) {
                log.info("Scheduled scan skipped for user {}: {}", user.getId(), e.getMessage());
            }
        }
    }
}
