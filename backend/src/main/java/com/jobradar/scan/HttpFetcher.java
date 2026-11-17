package com.jobradar.scan;

import com.jobradar.config.JobRadarProperties;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The single gateway for outbound scan traffic: per-host rate limit, timeout, retries, robots.txt,
 * manual redirect following and the private-network guard all live here.
 */
@Component
public class HttpFetcher {
    public record Response(int status, String body, String finalUrl) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Returns this response, or throws BlockedException / FetchException for an error status. */
        public Response requireOk() {
            if (ok()) {
                return this;
            }
            if (status == 401 || status == 403 || status == 429) {
                throw new BlockedException("HTTP " + status + " from " + finalUrl);
            }
            throw new FetchException("HTTP " + status + " from " + finalUrl);
        }
    }

    /** unavailableStatus is the HTTP status when robots.txt could not be read (5xx), otherwise 0. */
    private record CachedRobots(RobotsTxt rules, Instant expires, int unavailableStatus) {}

    /** These sites' terms forbid scraping, so no code path may reach them. */
    private static final Pattern FORBIDDEN_SITES =
            Pattern.compile("(^|\\.)(linkedin|naukri|indeed|glassdoor)\\.[a-z.]+$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration ROBOTS_TTL = Duration.ofHours(24);
    /** A robots.txt server error is usually temporary, so it is only trusted briefly before asking again. */
    static final Duration ROBOTS_ERROR_TTL = Duration.ofMinutes(5);
    private static final String HTML_ACCEPT = "text/html,application/xhtml+xml";

    private final DomainRateLimiter limiter;
    private final RestClient client;
    private final JobRadarProperties.Scan config;
    private final Map<String, CachedRobots> robots = new ConcurrentHashMap<>();
    /** How long a robots.txt 5xx keeps a site blocked; package-private so tests can shorten it. */
    Duration robotsErrorTtl = ROBOTS_ERROR_TTL;

    public HttpFetcher(DomainRateLimiter limiter, JobRadarProperties props) {
        this.limiter = limiter;
        this.config = props.scan();
        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                .build();
    }

    /** Public, documented ATS JSON API. These are meant for programmatic use, so robots.txt is not consulted. */
    public Response getJson(String url) {
        return run(HttpMethod.GET, url, null, MediaType.APPLICATION_JSON_VALUE, false, config.retries());
    }

    /** Same as getJson without retries; used when guessing whether a board exists. */
    public Response probeJson(String url) {
        return run(HttpMethod.GET, url, null, MediaType.APPLICATION_JSON_VALUE, false, 0);
    }

    /** A website page: robots.txt is enforced. */
    public Response getPage(String url) {
        return run(HttpMethod.GET, url, null, HTML_ACCEPT, true, config.retries());
    }

    /** Same as getPage without retries; used when guessing URLs such as /careers. */
    public Response probePage(String url) {
        return run(HttpMethod.GET, url, null, HTML_ACCEPT, true, 0);
    }

    /** JSON GET from a site endpoint that is not a documented public API (Workday): robots.txt is enforced. */
    public Response getSiteJson(String url) {
        return run(HttpMethod.GET, url, null, MediaType.APPLICATION_JSON_VALUE, true, config.retries());
    }

    /** True if a browser may load this URL under the same host rules as direct fetches. */
    public boolean isFetchAllowed(String url) {
        try {
            assertAllowedHost(toUri(url));
            return true;
        } catch (FetchException e) {
            return false;
        }
    }

    /** JSON POST to a site endpoint (Workday): robots.txt is enforced. */
    public Response postJson(String url, String body) {
        return run(HttpMethod.POST, url, body, MediaType.APPLICATION_JSON_VALUE, true, config.retries());
    }

    private Response run(HttpMethod method, String url, String body, String accept, boolean checkRobots, int retries) {
        for (int attempt = 0; ; attempt++) {
            try {
                Response response = followRedirects(method, url, body, accept, checkRobots);
                boolean transientError = response.status() >= 500 || response.status() == 429;
                if (!transientError || attempt >= retries) {
                    return response;
                }
            } catch (BlockedException e) {
                throw e;
            } catch (FetchException e) {
                if (attempt >= retries) {
                    throw e;
                }
            }
            backoff(attempt);
        }
    }

    private Response followRedirects(HttpMethod method, String startUrl, String body, String accept, boolean checkRobots) {
        String url = startUrl;
        HttpMethod currentMethod = method;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = toUri(url);
            assertAllowedHost(uri);
            if (checkRobots) {
                CachedRobots robotsRules = robotsFor(uri);
                if (robotsRules.unavailableStatus() != 0) {
                    throw new BlockedException("robots.txt could not be read (HTTP " + robotsRules.unavailableStatus()
                            + "), so the site was not scanned; try again later: " + url);
                }
                if (!robotsRules.rules().isAllowed(pathAndQuery(uri))) {
                    throw new BlockedException("Disallowed by robots.txt: " + url);
                }
            }
            limiter.acquire(uri.getHost());
            Response response = send(currentMethod, uri, body, accept);
            if (response.status() < 300 || response.status() >= 400 || response.body() == null
                    || !response.body().startsWith("location:")) {
                return response;
            }
            url = uri.resolve(response.body().substring("location:".length())).toString();
            if (response.status() != 307 && response.status() != 308) {
                currentMethod = HttpMethod.GET;
                body = null;
            }
        }
        throw new FetchException("Too many redirects from " + startUrl);
    }

    private Response send(HttpMethod method, URI uri, String body, String accept) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(uri).header(HttpHeaders.ACCEPT, accept);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                if (status >= 300 && status < 400 && location != null) {
                    return new Response(status, "location:" + location, uri.toString());
                }
                try (InputStream in = response.getBody()) {
                    byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
                    return new Response(status, new String(bytes, StandardCharsets.UTF_8), uri.toString());
                }
            });
        } catch (ResourceAccessException e) {
            throw new FetchException(describe(uri, e), e);
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid request to " + uri + ": " + e.getMessage(), e);
        }
    }

    private static String describe(URI uri, ResourceAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
            return "Timed out contacting " + uri.getHost();
        }
        return "Could not reach " + uri.getHost() + " (" + cause.getClass().getSimpleName() + ")";
    }

    private CachedRobots robotsFor(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        CachedRobots cached = robots.get(origin);
        if (cached != null && cached.expires().isAfter(Instant.now())) {
            return cached;
        }
        CachedRobots loaded = loadRobots(origin, uri.getHost());
        robots.put(origin, loaded);
        return loaded;
    }

    private CachedRobots loadRobots(String origin, String host) {
        Instant now = Instant.now();
        try {
            limiter.acquire(host);
            Response response = send(HttpMethod.GET, URI.create(origin + "/robots.txt"), null, "text/plain");
            if (response.ok()) {
                return new CachedRobots(RobotsTxt.parse(response.body(), "JobRadar"), now.plus(ROBOTS_TTL), 0);
            }
            // RFC 9309: a 5xx means the rules are unknown, so assume disallow; a 4xx means there are none.
            // The 5xx answer is cached only briefly, so the site is scanned again once robots.txt recovers.
            if (response.status() >= 500) {
                return new CachedRobots(RobotsTxt.DISALLOW_ALL, now.plus(robotsErrorTtl), response.status());
            }
            return new CachedRobots(RobotsTxt.ALLOW_ALL, now.plus(ROBOTS_TTL), 0);
        } catch (FetchException e) {
            // The page request that follows reports the real connection error; don't remember this result.
            return new CachedRobots(RobotsTxt.ALLOW_ALL, now, 0);
        }
    }

    private void assertAllowedHost(URI uri) {
        if (FORBIDDEN_SITES.matcher(uri.getHost()).find()) {
            throw new BlockedException("JobRadar does not scrape job portals (" + uri.getHost() + "); use the company's own career page");
        }
        if (config.allowPrivateHosts()) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    throw new BlockedException("Refusing to fetch private address: " + uri.getHost());
                }
            }
        } catch (UnknownHostException e) {
            throw new FetchException("Unknown host " + uri.getHost(), e);
        }
    }

    static boolean isPrivate(InetAddress a) {
        boolean uniqueLocalV6 = a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
        byte[] b = a.getAddress();
        boolean carrierNat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress() || uniqueLocalV6 || carrierNat;
    }

    private static URI toUri(String url) {
        try {
            URI uri = URI.create(url.trim().replace(" ", "%20"));
            if (uri.getHost() == null || uri.getScheme() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                throw new FetchException("Unsupported URL: " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new FetchException("Invalid URL: " + url, e);
        }
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(config.backoffMillis() << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted during retry backoff", e);
        }
    }

}
