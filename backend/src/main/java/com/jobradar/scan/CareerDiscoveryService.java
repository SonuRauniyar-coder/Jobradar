package com.jobradar.scan;

import com.jobradar.adapter.JobSourceAdapter;
import com.jobradar.common.UrlUtil;
import com.jobradar.company.SourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Works out where a company's jobs live: first from links on its own site, then by trying likely ATS slugs. */
@Service
public class CareerDiscoveryService {
    public record Detected(SourceType type, String slug, String careerUrl) {}

    private static final Set<String> RESERVED_SLUGS = Set.of("embed", "api", "jobs", "careers", "search", "assets", "static", "j");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "\\b(inc|incorporated|llc|ltd|limited|pvt|private|corp|corporation|plc|gmbh|co)\\b\\.?");

    private static final Map<SourceType, Pattern> LINK_PATTERNS = new LinkedHashMap<>();
    private static final Pattern WORKDAY_LINK = Pattern.compile(
            "https?://([a-z0-9-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}-[a-z]{2}/)?([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    static {
        int flags = Pattern.CASE_INSENSITIVE;
        LINK_PATTERNS.put(SourceType.GREENHOUSE, Pattern.compile(
                "(?:boards|job-boards)(?:\\.eu)?\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([a-z0-9_-]+)", flags));
        LINK_PATTERNS.put(SourceType.LEVER, Pattern.compile("jobs\\.lever\\.co/([a-z0-9_.-]+)", flags));
        LINK_PATTERNS.put(SourceType.ASHBY, Pattern.compile("jobs\\.ashbyhq\\.com/([a-z0-9_.-]+)", flags));
        LINK_PATTERNS.put(SourceType.SMARTRECRUITERS, Pattern.compile("(?:jobs|careers)\\.smartrecruiters\\.com/([a-z0-9_-]+)", flags));
        LINK_PATTERNS.put(SourceType.WORKABLE, Pattern.compile("apply\\.workable\\.com/([a-z0-9_-]+)", flags));
    }

    private final HttpFetcher http;
    private final List<JobSourceAdapter> probeOrder;

    public CareerDiscoveryService(HttpFetcher http, List<JobSourceAdapter> adapters) {
        this.http = http;
        this.probeOrder = adapters;
    }

    public Detected discover(String name, String website, String careerUrl) {
        // An ATS URL the user gave us is decisive, and needs no request to the company's site.
        Detected fromGivenUrl = careerUrl == null ? null : detectFromContent(careerUrl);
        if (fromGivenUrl != null) {
            return new Detected(fromGivenUrl.type(), fromGivenUrl.slug(), careerUrl);
        }

        BlockedException blocked = null;
        String workingPage = null;

        for (String candidate : candidatePages(website, careerUrl)) {
            try {
                HttpFetcher.Response response = http.probePage(candidate);
                if (!response.ok()) {
                    if (response.status() == 401 || response.status() == 403) {
                        blocked = new BlockedException("HTTP " + response.status() + " from " + candidate);
                    }
                    continue;
                }
                Detected linked = detectFromContent(response.finalUrl() + "\n" + response.body());
                if (linked != null) {
                    return new Detected(linked.type(), linked.slug(), response.finalUrl());
                }
                workingPage = response.finalUrl();
                break;
            } catch (BlockedException e) {
                blocked = e;
            } catch (FetchException e) {
                // A guessed URL that does not resolve is normal; move on to the next one.
            }
        }

        for (String slug : candidateSlugs(name, website)) {
            for (JobSourceAdapter adapter : probeOrder) {
                try {
                    if (adapter.probe(slug)) {
                        return new Detected(adapter.type(), slug, workingPage != null ? workingPage : careerUrl);
                    }
                } catch (FetchException e) {
                    // One ATS being unreachable must not end discovery for the others.
                }
            }
        }

        if (workingPage != null) {
            return new Detected(SourceType.GENERIC_HTML, null, workingPage);
        }
        if (blocked != null) {
            throw blocked;
        }
        return new Detected(SourceType.UNKNOWN, null, careerUrl);
    }

    /** Finds a known ATS link inside a URL or page source. */
    static Detected detectFromContent(String content) {
        Matcher workday = WORKDAY_LINK.matcher(content);
        if (workday.find()) {
            return new Detected(SourceType.WORKDAY,
                    workday.group(1) + "." + workday.group(2) + "/" + workday.group(3), null);
        }
        for (Map.Entry<SourceType, Pattern> entry : LINK_PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(content);
            while (matcher.find()) {
                String slug = matcher.group(1);
                if (!RESERVED_SLUGS.contains(slug.toLowerCase(Locale.ROOT))) {
                    return new Detected(entry.getKey(), slug, null);
                }
            }
        }
        return null;
    }

    static List<String> candidatePages(String website, String careerUrl) {
        List<String> pages = new ArrayList<>();
        if (careerUrl != null) {
            pages.add(careerUrl);
        }
        String domain = UrlUtil.domain(website);
        if (domain != null) {
            String base = website.replaceAll("/+$", "");
            pages.add(base + "/careers");
            pages.add(base + "/jobs");
            pages.add(base + "/careers/jobs");
            pages.add("https://careers." + domain);
            pages.add("https://jobs." + domain);
        }
        return pages;
    }

    /** At most two guesses: the shared ATS hosts are rate limited, so every extra guess costs real scan time. */
    static List<String> candidateSlugs(String name, String website) {
        Set<String> slugs = new LinkedHashSet<>();
        String domain = UrlUtil.domain(website);
        if (domain != null) {
            slugs.add(domain.split("\\.")[0]);
        }
        String cleaned = COMPANY_SUFFIX.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
        if (!cleaned.isEmpty()) {
            slugs.add(cleaned.replace(" ", ""));
            slugs.add(cleaned.replace(" ", "-"));
        }
        return slugs.stream().limit(2).toList();
    }
}
