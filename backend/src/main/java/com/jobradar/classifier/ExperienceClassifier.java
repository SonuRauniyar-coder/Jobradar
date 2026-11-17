package com.jobradar.classifier;

import com.jobradar.job.JobEnums.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Decides FRESHER / EXPERIENCED from a job's title and text. Rules, strongest first:
 * <ol>
 *   <li>unmistakably entry-level title (Intern, Trainee, ...)</li>
 *   <li>seniority title (Senior, Lead, Manager, ...)</li>
 *   <li>stated years of experience</li>
 *   <li>the source's own experience-level field</li>
 *   <li>softer entry-level signals (Junior, Graduate, "freshers can apply", ...)</li>
 * </ol>
 * Stated years outrank "Junior" because that word often appears on roles asking for years of experience.
 * "Associate" is not an entry-level signal: at banks and large firms it is a corporate grade held by experienced staff.
 */
@Service
public class ExperienceClassifier {
    public record Result(Category category, Integer minYears, Integer maxYears, String experienceRaw, String reason) {}

    private record YearsMatch(int min, Integer max, String raw) {}

    private static final int I = Pattern.CASE_INSENSITIVE;

    private static final Pattern STRONG_ENTRY_TITLE = Pattern.compile(
            "\\b(interns?|internship|trainee|apprentice(ship)?|freshers?|new[ -]?grad(uate)?s?)\\b", I);
    /** Case-sensitive so the everyday word "get" does not count as Graduate Engineer Trainee. */
    private static final Pattern GET_TITLE = Pattern.compile("\\bGET\\b");
    private static final Pattern SENIOR_TITLE = Pattern.compile(
            "\\b(senior|sr\\.?|lead(?!\\s+gen)|principal|staff|manager|architect|director|head of|vice president|vp)\\b", I);
    private static final Pattern SOFT_ENTRY_TITLE = Pattern.compile("\\b(graduate|junior|jr\\.?|entry|campus)\\b", I);
    private static final Pattern ENTRY_PHRASE = Pattern.compile(
            "\\bfreshers?\\b|\\bentry[ -]level\\b|\\bnew[ -]grad(uate)?s?\\b|\\brecent graduates?\\b|\\bcampus (hire|hiring|recruit\\w*)\\b"
                    + "|\\bno (prior |previous )?experience (is )?(required|needed)\\b", I);
    private static final Pattern BATCH = Pattern.compile(
            "\\b20[2-3]\\d(\\s*[/&,-]\\s*(20)?[2-3]\\d)*\\s*(batch|pass[- ]?outs?|graduates?)\\b|\\bbatch(es)? of 20[2-3]\\d\\b", I);
    private static final Pattern LEVEL_FIELD = Pattern.compile("^Experience level:\\s*(.+)$", I | Pattern.MULTILINE);

    private static final String UNIT = "(?:years?|yrs?)";
    private static final Pattern RANGE = Pattern.compile("\\b(\\d{1,2})(?:\\.\\d)?\\s*(?:-|–|—|to)\\s*(\\d{1,2})(?:\\.\\d)?\\s*\\+?\\s*" + UNIT + "\\b", I);
    private static final Pattern PLUS = Pattern.compile("\\b(\\d{1,2})\\s*\\+\\s*" + UNIT + "\\b", I);
    private static final Pattern MINIMUM = Pattern.compile("\\b(?:minimum|min\\.?|at\\s?least)\\s*(?:of\\s*)?(\\d{1,2})\\s*\\+?\\s*" + UNIT + "\\b", I);
    private static final Pattern PLAIN = Pattern.compile("\\b(\\d{1,2})\\s*" + UNIT + "\\s+(?:of\\s+)?(?:[\\w/+#.-]+\\s+){0,3}experience\\b", I);

    private static final Pattern EXPERIENCE_CONTEXT = Pattern.compile("experience|\\bexp\\b|background|hands[- ]on|proven|required", I);
    private static final Pattern NOT_EXPERIENCE = Pattern.compile("\\byears? (old|ago)\\b|of age\\b|in business\\b|anniversary", I);
    /** Text this short is an experience field in its own right, so a bare "0-2 years" counts. */
    private static final int SHORT_TEXT = 120;
    private static final int MAX_PLAUSIBLE_YEARS = 40;

    public Result classify(String title, String description, String employmentType) {
        String safeTitle = title == null ? "" : title;
        String text = description == null ? "" : description;
        YearsMatch years = findYears(safeTitle, text);
        Integer min = years == null ? null : years.min();
        Integer max = years == null ? null : years.max();
        String raw = years == null ? null : years.raw();

        Matcher strong = STRONG_ENTRY_TITLE.matcher(safeTitle);
        if (strong.find()) {
            return new Result(Category.FRESHER, min, max, raw, "Title contains \"" + strong.group() + "\"");
        }
        if (GET_TITLE.matcher(safeTitle).find()) {
            return new Result(Category.FRESHER, min, max, raw, "Title contains \"GET\" (Graduate Engineer Trainee)");
        }
        if (employmentType != null && employmentType.toLowerCase(Locale.ROOT).contains("intern")) {
            return new Result(Category.FRESHER, min, max, raw, "Employment type is \"" + employmentType + "\"");
        }
        Matcher senior = SENIOR_TITLE.matcher(safeTitle);
        if (senior.find()) {
            return new Result(Category.EXPERIENCED, min, max, raw, "Title contains \"" + senior.group() + "\"");
        }
        if (years != null) {
            Category category = years.min() == 0 ? Category.FRESHER : Category.EXPERIENCED;
            return new Result(category, min, max, raw, "Asks for \"" + raw + "\" (minimum " + years.min() + " years)");
        }
        Matcher level = LEVEL_FIELD.matcher(text);
        if (level.find()) {
            String value = level.group(1).trim().toLowerCase(Locale.ROOT);
            if (value.contains("entry") || value.contains("intern")) {
                return new Result(Category.FRESHER, null, null, level.group(1).trim(), "Listed experience level is \"" + level.group(1).trim() + "\"");
            }
            if (value.matches(".*(mid|senior|director|executive|manager|experienced).*")) {
                return new Result(Category.EXPERIENCED, null, null, level.group(1).trim(), "Listed experience level is \"" + level.group(1).trim() + "\"");
            }
        }
        Matcher soft = SOFT_ENTRY_TITLE.matcher(safeTitle);
        if (soft.find()) {
            return new Result(Category.FRESHER, null, null, null, "Title contains \"" + soft.group() + "\"");
        }
        Matcher phrase = ENTRY_PHRASE.matcher(text);
        if (phrase.find()) {
            return new Result(Category.FRESHER, null, null, phrase.group(), "Description mentions \"" + phrase.group() + "\"");
        }
        Matcher batch = BATCH.matcher(text);
        if (batch.find()) {
            return new Result(Category.FRESHER, null, null, batch.group(), "Description mentions \"" + batch.group() + "\"");
        }
        return new Result(Category.UNSPECIFIED, null, null, null, "No experience information found");
    }

    /**
     * Returns the highest minimum stated. Postings often list the main requirement next to smaller
     * ones ("5+ years overall, 2+ years of Kafka"), and the main requirement is what decides eligibility.
     */
    private YearsMatch findYears(String title, String text) {
        List<YearsMatch> found = new ArrayList<>();
        collect(title, true, found);
        collect(text, text.length() <= SHORT_TEXT, found);
        YearsMatch best = null;
        for (YearsMatch match : found) {
            if (best == null || match.min() > best.min()) {
                best = match;
            }
        }
        return best;
    }

    private void collect(String text, boolean trustBareMatches, List<YearsMatch> out) {
        List<int[]> rangeSpans = new ArrayList<>();
        Matcher range = RANGE.matcher(text);
        while (range.find()) {
            int min = Integer.parseInt(range.group(1));
            int max = Integer.parseInt(range.group(2));
            if (min <= max && max <= MAX_PLAUSIBLE_YEARS && accepted(text, range.start(), range.end(), trustBareMatches)) {
                out.add(new YearsMatch(min, max, range.group()));
                rangeSpans.add(new int[] {range.start(), range.end()});
            }
        }
        Matcher plus = PLUS.matcher(text);
        while (plus.find()) {
            addOpenEnded(text, plus, trustBareMatches, rangeSpans, out);
        }
        Matcher minimum = MINIMUM.matcher(text);
        while (minimum.find()) {
            addOpenEnded(text, minimum, true, rangeSpans, out);
        }
        Matcher plain = PLAIN.matcher(text);
        while (plain.find()) {
            addOpenEnded(text, plain, true, rangeSpans, out);
        }
    }

    /** Skips matches inside an accepted range: the "5 years of experience" in "3-5 years of experience" is not a minimum of 5. */
    private void addOpenEnded(String text, Matcher matcher, boolean trust, List<int[]> rangeSpans, List<YearsMatch> out) {
        for (int[] span : rangeSpans) {
            if (matcher.start() < span[1] && matcher.end() > span[0]) {
                return;
            }
        }
        int min = Integer.parseInt(matcher.group(1));
        if (min <= MAX_PLAUSIBLE_YEARS && accepted(text, matcher.start(), matcher.end(), trust)) {
            out.add(new YearsMatch(min, null, matcher.group()));
        }
    }

    private boolean accepted(String text, int start, int end, boolean trustBareMatches) {
        String window = text.substring(Math.max(0, start - 40), Math.min(text.length(), end + 80));
        String tight = text.substring(start, Math.min(text.length(), end + 16));
        if (NOT_EXPERIENCE.matcher(tight).find()) {
            return false;
        }
        return trustBareMatches || EXPERIENCE_CONTEXT.matcher(window).find();
    }
}
