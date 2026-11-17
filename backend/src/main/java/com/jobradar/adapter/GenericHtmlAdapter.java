package com.jobradar.adapter;

import com.jobradar.common.TextUtil;
import com.jobradar.common.UrlUtil;
import com.jobradar.company.SourceType;
import com.jobradar.config.JobRadarProperties;
import com.jobradar.scan.BlockedException;
import com.jobradar.scan.FetchException;
import com.jobradar.scan.HttpFetcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Fallback for companies without a known ATS: reads the career page, follows job links, extracts each job. */
@Component
public class GenericHtmlAdapter implements JobSourceAdapter {
    private static final Pattern JOB_LINK = Pattern.compile("job|career|position|opening|vacanc|requisition", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_A_JOB = Pattern.compile(
            "login|signin|sign-in|register|privacy|cookie|terms|blog|news|press|about|contact|linkedin|facebook|twitter"
                    + "|instagram|youtube|feed|rss|atom|\\.xml$|\\.pdf$|\\.jpg$|\\.png$"
                    + "|/(types?|categor(y|ies)|locations?|departments?|teams?|tags?|search|benefits|culture|faqs?)(/|\\?|$)", Pattern.CASE_INSENSITIVE);
    /** A number in the URL usually means a specific posting rather than a landing page, so those are read first. */
    private static final Pattern POSTING_ID = Pattern.compile("\\d{2,}");
    private static final Pattern LOOKS_LIKE_JOB = Pattern.compile(
            "responsibilit|qualification|requirement|apply now|what you.ll do|years of experience|job description", Pattern.CASE_INSENSITIVE);
    /** Below this much visible text, a page is treated as an unrendered JavaScript shell. */
    private static final int JS_SHELL_TEXT_LENGTH = 400;

    private final HttpFetcher http;
    private final BrowserRenderer renderer;
    private final int maxJobPages;

    public GenericHtmlAdapter(HttpFetcher http, BrowserRenderer renderer, JobRadarProperties props) {
        this.http = http;
        this.renderer = renderer;
        this.maxJobPages = props.scan().maxJobPagesPerCompany();
    }

    @Override
    public SourceType type() {
        return SourceType.GENERIC_HTML;
    }

    @Override
    public FetchResult fetch(ScanTarget target) {
        if (target.careerUrl() == null) {
            throw new FetchException("No career page URL is known for this company");
        }
        HttpFetcher.Response response = http.getPage(target.careerUrl()).requireOk();
        String pageUrl = response.finalUrl();
        Document listing = Jsoup.parse(response.body(), pageUrl);

        List<RawJob> embedded = JsonLdParser.parse(listing, pageUrl);
        Map<String, String> links = findJobLinks(listing, pageUrl);

        if (embedded.isEmpty() && links.isEmpty() && listing.body().text().length() < JS_SHELL_TEXT_LENGTH) {
            Optional<String> rendered = renderer.render(pageUrl);
            if (rendered.isPresent()) {
                listing = Jsoup.parse(rendered.get(), pageUrl);
                embedded = JsonLdParser.parse(listing, pageUrl);
                links = findJobLinks(listing, pageUrl);
            }
        }

        Map<String, RawJob> jobs = new LinkedHashMap<>();
        embedded.forEach(job -> jobs.put(job.applyUrl(), job));

        int attempted = 0, failed = 0;
        boolean truncated = false;
        BlockedException lastBlock = null;
        for (Map.Entry<String, String> link : links.entrySet()) {
            if (jobs.containsKey(link.getKey())) {
                continue;
            }
            if (attempted >= maxJobPages) {
                truncated = true;
                break;
            }
            attempted++;
            try {
                readJobPage(link.getKey(), link.getValue()).forEach(job -> jobs.put(job.applyUrl(), job));
            } catch (BlockedException e) {
                failed++;
                lastBlock = e;
            } catch (FetchException e) {
                failed++;
            }
        }

        List<RawJob> result = new ArrayList<>(jobs.values());
        if (result.isEmpty()) {
            if (lastBlock != null) {
                throw lastBlock;
            }
            return FetchResult.partial(result, "The career page loaded, but no job listings could be recognised on it");
        }
        if (truncated) {
            return FetchResult.partial(result, "Stopped after " + maxJobPages + " job pages; more links were found");
        }
        if (failed > 0) {
            return FetchResult.partial(result, failed + " of " + attempted + " job pages could not be read");
        }
        return FetchResult.complete(result);
    }

    /** Finds likely job links, keyed by URL with the link text as the value. Only follows the company's own domain. */
    static Map<String, String> findJobLinks(Document document, String pageUrl) {
        String siteDomain = UrlUtil.domain(pageUrl);
        String listingPath = stripFragment(pageUrl).replaceAll("/+$", "");
        Map<String, String> links = new LinkedHashMap<>();
        for (Element anchor : document.select("a[href]")) {
            String url = stripFragment(anchor.absUrl("href"));
            String domain = UrlUtil.domain(url);
            if (domain == null || siteDomain == null
                    || !(domain.equals(siteDomain) || domain.endsWith("." + siteDomain) || siteDomain.endsWith("." + domain))) {
                continue;
            }
            String path = url.replaceFirst("^https?://[^/]+", "");
            if (!JOB_LINK.matcher(url).find() || NOT_A_JOB.matcher(path).find()) {
                continue;
            }
            String trimmed = url.replaceAll("/+$", "");
            // Links that only lead back to, or above, the listing page are navigation.
            if (trimmed.equals(listingPath) || listingPath.startsWith(trimmed) || isSectionRoot(path)) {
                continue;
            }
            links.putIfAbsent(url, anchor.text().trim());
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        links.forEach((url, text) -> { if (POSTING_ID.matcher(url).find()) ordered.put(url, text); });
        links.forEach(ordered::putIfAbsent);
        return ordered;
    }

    private static boolean isSectionRoot(String path) {
        String clean = path.replaceAll("[?#].*$", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        return Set.of("", "/careers", "/jobs", "/career", "/job", "/careers/jobs", "/openings", "/positions").contains(clean);
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    private List<RawJob> readJobPage(String url, String linkText) {
        HttpFetcher.Response response = http.getPage(url).requireOk();
        Document page = Jsoup.parse(response.body(), response.finalUrl());

        List<RawJob> structured = JsonLdParser.parse(page, response.finalUrl());
        if (!structured.isEmpty()) {
            return structured;
        }
        Element heading = page.selectFirst("h1");
        String title = heading != null && !heading.text().isBlank() ? heading.text() : linkText;
        page.select("script, style, noscript, nav, header, footer").remove();
        Element main = page.selectFirst("main, article, [role=main]");
        Element content = main != null ? main : page.body();
        if (title == null || title.isBlank() || content == null || !LOOKS_LIKE_JOB.matcher(content.text()).find()) {
            return List.of();
        }
        return List.of(new RawJob(null, TextUtil.truncate(title, 500), null, findLocation(page), null, null,
                TextUtil.htmlToText(content.html()), findApplyUrl(page, response.finalUrl()), null, response.finalUrl()));
    }

    private static String findLocation(Document page) {
        Set<String> candidates = new LinkedHashSet<>();
        for (Element e : page.select("[class*=location], [id*=location], [itemprop=jobLocation]")) {
            String text = e.text().trim();
            if (!text.isEmpty() && text.length() < 120) {
                candidates.add(text);
            }
        }
        return candidates.stream().findFirst().orElse(null);
    }

    private static String findApplyUrl(Document page, String fallback) {
        for (Element anchor : page.select("a[href]")) {
            String label = (anchor.text() + " " + anchor.attr("href")).toLowerCase(Locale.ROOT);
            String url = anchor.absUrl("href");
            if (label.contains("apply") && url.startsWith("http")) {
                return stripFragment(url);
            }
        }
        return fallback;
    }
}
