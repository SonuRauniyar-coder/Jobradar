package com.jobradar.common;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class UrlUtil {
    /** Matches the VARCHAR(1000) URL columns on companies. */
    public static final int MAX_LENGTH = 1000;

    private static final String ALLOWED_ASCII =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=";

    private UrlUtil() {}

    /**
     * Returns a clean http(s) URL, null for blank input, or throws IllegalArgumentException if invalid.
     * International host names are converted to their ASCII form, and characters that are not allowed in a URL
     * (spaces, "|", non-ASCII text) are percent-encoded. The result is at most {@link #MAX_LENGTH} characters.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            value = "https://" + value;
        }
        int schemeEnd = value.indexOf("://");
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            throw invalid(raw);
        }
        String rest = value.substring(schemeEnd + 3);
        int authorityEnd = firstIndexOf(rest, "/?#");
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        String tail = authorityEnd < 0 ? "" : rest.substring(authorityEnd);

        int at = authority.lastIndexOf('@');
        String userInfo = at < 0 ? "" : authority.substring(0, at + 1);
        String hostPort = authority.substring(at + 1);
        String host;
        String port;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw invalid(raw);
            }
            host = hostPort.substring(0, close + 1);
            port = hostPort.substring(close + 1);
        } else {
            int colon = hostPort.indexOf(':');
            host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            port = colon < 0 ? "" : hostPort.substring(colon);
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw invalid(raw);
            }
            if (host.contains("_")) {
                throw new IllegalArgumentException("Host names containing \"_\" are not supported: " + raw);
            }
        }
        String result = scheme + "://" + encode(userInfo) + host + port + encode(tail);
        try {
            URI uri = new URI(result);
            if (uri.getHost() == null || !uri.getHost().contains(".")) {
                throw invalid(raw);
            }
        } catch (URISyntaxException e) {
            throw invalid(raw);
        }
        if (result.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("URL is longer than " + MAX_LENGTH + " characters");
        }
        return result;
    }

    /** True for an absolute http or https URL; used to keep javascript:, data: and similar links out of the app. */
    public static boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Lower-case host without a leading "www.", or null if the URL has no usable host. */
    public static String domain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static IllegalArgumentException invalid(String raw) {
        return new IllegalArgumentException("Invalid URL: " + raw);
    }

    private static int firstIndexOf(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Percent-encodes anything outside the characters a URI may contain; existing %XX escapes are kept. */
    private static String encode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.append('%');
            } else if (cp < 128 && ALLOWED_ASCII.indexOf(cp) >= 0) {
                out.append((char) cp);
            } else {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xff));
                }
            }
            i += len;
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
