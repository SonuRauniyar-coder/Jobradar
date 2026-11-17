package com.jobradar.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

public final class TextUtil {
    private static final Set<String> BLOCKS = Set.of("p", "div", "br", "li", "ul", "ol", "tr", "section", "article",
            "h1", "h2", "h3", "h4", "h5", "h6");

    private TextUtil() {}

    /** Converts HTML (including entity-escaped HTML, as Greenhouse returns) to plain text, keeping line breaks. */
    public static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String source = html.contains("&lt;") ? Parser.unescapeEntities(html, false) : html;
        Element body = Jsoup.parse(source).body();
        body.select("script, style, noscript").remove();
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.text());
                } else if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    out.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
                    out.append('\n');
                }
            }
        }, body);
        return out.toString().replace(' ', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** PostgreSQL text columns reject the NUL character, so it is removed from anything that will be stored. */
    public static String stripNul(String value) {
        return value == null || value.indexOf('\u0000') < 0 ? value : value.replace("\u0000", "");
    }

    public static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
