package com.jobradar.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Minimal RFC 9309 matcher: picks our group (or "*") and applies longest-match, with Allow winning ties. */
public final class RobotsTxt {
    private record Rule(boolean allow, int length, Pattern pattern) {}

    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of());
    public static final RobotsTxt DISALLOW_ALL = new RobotsTxt(List.of(new Rule(false, 1, Pattern.compile("/.*"))));

    private final List<Rule> rules;

    private RobotsTxt(List<Rule> rules) {
        this.rules = rules;
    }

    public static RobotsTxt parse(String content, String productToken) {
        String token = productToken.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean sawSpecific = false;

        List<String> agents = new ArrayList<>();
        boolean inRules = false;
        for (String rawLine : content.split("\\R")) {
            int hash = rawLine.indexOf('#');
            String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (inRules) {
                    agents.clear();
                    inRules = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                sawSpecific |= value.equalsIgnoreCase(token);
            } else if (field.equals("allow") || field.equals("disallow")) {
                inRules = true;
                if (value.isEmpty()) {
                    continue; // an empty Disallow allows everything
                }
                Rule rule = new Rule(field.equals("allow"), value.length(), toPattern(value));
                if (agents.contains(token)) {
                    specific.add(rule);
                }
                if (agents.contains("*")) {
                    wildcard.add(rule);
                }
            }
        }
        return new RobotsTxt(sawSpecific ? specific : wildcard);
    }

    public boolean isAllowed(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.pattern.matcher(path).lookingAt()
                    && (best == null || rule.length > best.length || (rule.length == best.length && rule.allow))) {
                best = rule;
            }
        }
        return best == null || best.allow;
    }

    private static Pattern toPattern(String value) {
        boolean anchored = value.endsWith("$");
        String body = anchored ? value.substring(0, value.length() - 1) : value;
        StringBuilder regex = new StringBuilder();
        String[] parts = body.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        if (anchored) {
            regex.append('$');
        }
        return Pattern.compile(regex.toString());
    }
}
