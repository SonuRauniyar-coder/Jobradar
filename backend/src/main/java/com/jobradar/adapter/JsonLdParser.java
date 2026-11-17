package com.jobradar.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.common.TextUtil;
import com.jobradar.common.UrlUtil;
import com.jobradar.scan.FetchException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Reads schema.org JobPosting data, which sites publish for search engines and is more reliable than page layout. */
final class JsonLdParser {
    private JsonLdParser() {}

    static List<RawJob> parse(Document document, String pageUrl) {
        List<RawJob> jobs = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                collect(JsonSupport.parse(script.data()), pageUrl, jobs);
            } catch (FetchException e) {
                // Sites often ship broken JSON-LD; the other blocks and the HTML fallback still work.
            }
        }
        return jobs;
    }

    private static void collect(JsonNode node, String pageUrl, List<RawJob> out) {
        if (node.isArray()) {
            node.forEach(item -> collect(item, pageUrl, out));
        } else if (node.isObject()) {
            if (isJobPosting(node)) {
                out.add(toJob(node, pageUrl));
            }
            collect(node.path("@graph"), pageUrl, out);
            node.path("itemListElement").forEach(item -> collect(item.has("item") ? item.get("item") : item, pageUrl, out));
        }
    }

    private static boolean isJobPosting(JsonNode node) {
        JsonNode type = node.path("@type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("JobPosting".equals(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return "JobPosting".equals(type.asText());
    }

    private static RawJob toJob(JsonNode node, String pageUrl) {
        String link = resolveLink(JsonSupport.text(node, "url"), pageUrl);
        String identifier = node.path("identifier").isObject()
                ? JsonSupport.text(node.path("identifier"), "value") : JsonSupport.text(node, "identifier");
        String hint = "TELECOMMUTE".equalsIgnoreCase(JsonSupport.text(node, "jobLocationType")) ? "remote" : null;
        return new RawJob(identifier, TextUtil.htmlToText(JsonSupport.text(node, "title")), null, location(node.path("jobLocation")),
                hint, employmentType(node.path("employmentType")), description(node), link,
                JsonSupport.parseInstant(JsonSupport.text(node, "datePosted")), link);
    }

    /** The posting's own URL, resolved against the page; anything that is not http(s), such as javascript:, falls back to the page. */
    static String resolveLink(String url, String pageUrl) {
        if (url == null || url.isBlank()) {
            return pageUrl;
        }
        try {
            String resolved = java.net.URI.create(pageUrl).resolve(url.trim()).toString();
            return UrlUtil.isHttpUrl(resolved) ? resolved : pageUrl;
        } catch (IllegalArgumentException e) {
            return UrlUtil.isHttpUrl(url) ? url.trim() : pageUrl;
        }
    }

    private static String description(JsonNode node) {
        String body = TextUtil.htmlToText(JsonSupport.text(node, "description"));
        JsonNode requirement = node.path("experienceRequirements");
        String experience = null;
        if (requirement.isTextual()) {
            experience = requirement.asText();
        } else if (requirement.hasNonNull("monthsOfExperience")) {
            experience = (requirement.get("monthsOfExperience").asInt() / 12) + "+ years";
        }
        if (experience == null || experience.isBlank()) {
            return body;
        }
        return "Experience: " + experience + (body == null ? "" : "\n\n" + body);
    }

    private static String employmentType(JsonNode node) {
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(v -> values.add(v.asText()));
            return values.isEmpty() ? null : String.join(", ", values);
        }
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String location(JsonNode node) {
        Set<String> places = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(place -> addPlace(place, places));
        } else {
            addPlace(node, places);
        }
        return places.isEmpty() ? null : String.join("; ", places);
    }

    private static void addPlace(JsonNode place, Set<String> out) {
        JsonNode address = place.path("address");
        if (address.isTextual()) {
            out.add(address.asText());
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String field : List.of("addressLocality", "addressRegion", "addressCountry")) {
            JsonNode value = address.path(field);
            String text = value.isObject() ? JsonSupport.text(value, "name") : JsonSupport.text(address, field);
            if (text != null && !parts.contains(text)) {
                parts.add(text);
            }
        }
        if (!parts.isEmpty()) {
            out.add(String.join(", ", parts));
        }
    }
}
