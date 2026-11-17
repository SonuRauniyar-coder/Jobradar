package com.jobradar.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.config.JobRadarProperties;
import com.jobradar.job.JobExporterAccess;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FetchSafetyTest {
    private final HttpFetcher fetcher = new HttpFetcher(new DomainRateLimiter(), new JobRadarProperties(null, null, null,
            new JobRadarProperties.Scan("UA", 20, 2, 1000, 25, false, true, "", "", false), null));

    @ParameterizedTest
    @ValueSource(strings = {"https://www.linkedin.com/jobs", "https://in.linkedin.com/x", "https://www.naukri.com/a",
            "https://indeed.com/q", "https://in.indeed.com/q", "https://www.glassdoor.co.in/Job", "https://glassdoor.com"})
    void refusesJobPortals(String url) {
        assertThat(fetcher.isFetchAllowed(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://boards.greenhouse.io/a", "https://notindeed.com/x", "https://careers.example.com/"})
    void allowsOtherSites(String url) {
        assertThat(fetcher.isFetchAllowed(url)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"127.0.0.1,true", "10.1.2.3,true", "192.168.0.1,true", "172.16.0.9,true", "169.254.169.254,true", "100.64.0.1,true",
            "0.0.0.0,true", "::1,true", "fd00::1,true", "fe80::1,true", "8.8.8.8,false", "2606:4700:4700::1111,false"})
    void recognisesPrivateAddresses(String ip, boolean isPrivate) throws UnknownHostException {
        assertThat(HttpFetcher.isPrivate(InetAddress.getByName(ip))).isEqualTo(isPrivate);
    }

    @Test
    void rateLimiterSpacesRequestsPerHostOnly() {
        DomainRateLimiter limiter = new DomainRateLimiter();
        limiter.setRatePerSecond(10);
        long start = System.nanoTime();
        limiter.acquire("a.com");
        limiter.acquire("b.com");
        assertThat(elapsedMillis(start)).isLessThan(90);
        limiter.acquire("a.com");
        limiter.acquire("a.com");
        assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(190);
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void exportNeutralisesSpreadsheetFormulas() {
        assertThat(JobExporterAccess.safe("=1+1")).isEqualTo("'=1+1");
        assertThat(JobExporterAccess.safe("+cmd")).isEqualTo("'+cmd");
        assertThat(JobExporterAccess.safe("-2")).isEqualTo("'-2");
        assertThat(JobExporterAccess.safe("@x")).isEqualTo("'@x");
        assertThat(JobExporterAccess.safe("Normal")).isEqualTo("Normal");
    }
}
