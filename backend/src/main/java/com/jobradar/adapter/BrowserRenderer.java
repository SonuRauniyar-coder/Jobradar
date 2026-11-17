package com.jobradar.adapter;

import com.jobradar.config.JobRadarProperties;
import com.jobradar.scan.HttpFetcher;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renders JavaScript career pages. Chromium starts only on first use. Playwright objects are not
 * thread-safe, so renders run one at a time.
 */
@Component
public class BrowserRenderer {
    private static final Logger log = LoggerFactory.getLogger(BrowserRenderer.class);
    private static final Set<String> SKIPPED_RESOURCES = Set.of("image", "media", "font", "stylesheet");
    private static final int SETTLE_MILLIS = 2500;

    private final HttpFetcher http;
    private final JobRadarProperties.Scan config;
    private Playwright playwright;
    private Browser browser;
    private boolean unavailable;

    public BrowserRenderer(HttpFetcher http, JobRadarProperties props) {
        this.http = http;
        this.config = props.scan();
    }

    /** Returns the rendered HTML, or empty if rendering is disabled or fails. */
    public synchronized Optional<String> render(String url) {
        if (!config.playwrightEnabled() || unavailable) {
            return Optional.empty();
        }
        try {
            if (browser == null) {
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            }
            try (BrowserContext context = browser.newContext(new Browser.NewContextOptions().setUserAgent(config.userAgent()))) {
                Page page = context.newPage();
                page.route("**/*", route -> {
                    boolean skip = SKIPPED_RESOURCES.contains(route.request().resourceType());
                    if (skip || !http.isFetchAllowed(route.request().url())) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                });
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(config.timeoutSeconds() * 1000.0)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(SETTLE_MILLIS);
                return Optional.of(page.content());
            }
        } catch (PlaywrightException e) {
            if (browser == null) {
                unavailable = true;
                log.warn("Playwright browser could not start; JavaScript pages will be skipped: {}", e.getMessage());
            } else {
                log.info("Could not render {}: {}", url, e.getMessage());
            }
            return Optional.empty();
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (playwright != null) {
            playwright.close();
        }
    }
}
