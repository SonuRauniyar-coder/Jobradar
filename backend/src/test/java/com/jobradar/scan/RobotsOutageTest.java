package com.jobradar.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobradar.config.JobRadarProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A robots.txt outage must not keep a site blocked after robots.txt is healthy again. */
class RobotsOutageTest {
    private HttpServer server;
    private final AtomicInteger robotsStatus = new AtomicInteger(503);
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> reply(exchange, robotsStatus.get(), "User-agent: *\nAllow: /\n"));
        server.createContext("/careers", exchange -> reply(exchange, 200, "<html>jobs</html>"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpFetcher fetcher() {
        HttpFetcher fetcher = new HttpFetcher(new DomainRateLimiter(), new JobRadarProperties(null, null, null,
                new JobRadarProperties.Scan("UA", 5, 0, 10, 25, false, true, "", "", false), null));
        return fetcher;
    }

    @Test
    void outageBlocksWithAClearMessageAndIsOnlyCachedBriefly() {
        HttpFetcher fetcher = fetcher();
        assertThatThrownBy(() -> fetcher.getPage(base + "/careers"))
                .isInstanceOf(BlockedException.class)
                .hasMessageContaining("robots.txt could not be read (HTTP 503)");
        assertThat(HttpFetcher.ROBOTS_ERROR_TTL).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void siteIsScannedAgainOnceRobotsTxtRecovers() {
        HttpFetcher fetcher = fetcher();
        fetcher.robotsErrorTtl = Duration.ZERO;
        assertThatThrownBy(() -> fetcher.getPage(base + "/careers")).isInstanceOf(BlockedException.class);

        robotsStatus.set(200);
        assertThat(fetcher.getPage(base + "/careers").status()).isEqualTo(200);
    }
}
