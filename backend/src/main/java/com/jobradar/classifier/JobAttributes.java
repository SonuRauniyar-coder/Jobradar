package com.jobradar.classifier;

import com.jobradar.job.JobEnums.RoleCategory;
import com.jobradar.job.JobEnums.WorkMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Derives role category, work mode, skills and match score from job text. */
public final class JobAttributes {
    private static final int I = Pattern.CASE_INSENSITIVE;

    /** Checked in order: a "Data Platform QA" role is QA, and "Full Stack" must be tested before Frontend and Backend. */
    private static final Map<RoleCategory, Pattern> ROLES = new LinkedHashMap<>();
    private static final Map<String, Pattern> SKILLS = new LinkedHashMap<>();

    static {
        ROLES.put(RoleCategory.QA_TESTING, Pattern.compile("\\b(qa|sdet|test(er|ing)?|quality (assurance|engineer)|automation engineer)\\b", I));
        ROLES.put(RoleCategory.DEVOPS, Pattern.compile("\\b(devops|devsecops|sre|site reliability|platform engineer|infrastructure|cloud engineer|kubernetes)\\b", I));
        ROLES.put(RoleCategory.NETWORK, Pattern.compile("\\b(network (engineer|administrator|admin|analyst|architect|security)|noc|ccna|ccnp)\\b", I));
        ROLES.put(RoleCategory.DATA, Pattern.compile("\\b(data|machine learning|ml|analytics|business intelligence|bi developer|ai engineer)\\b", I));
        ROLES.put(RoleCategory.FULL_STACK, Pattern.compile("\\bfull[ -]?stack\\b", I));
        ROLES.put(RoleCategory.FRONTEND, Pattern.compile("\\b(front[ -]?end|react|angular|vue|ui (engineer|developer)|web developer)\\b", I));
        ROLES.put(RoleCategory.BACKEND, Pattern.compile("\\b(back[ -]?end|java|spring|python developer|golang|go developer|node(\\.js)? developer|\\.net|php|server[ -]side|api engineer)\\b", I));

        for (String skill : List.of("Java", "Spring Boot", "Spring", "Python", "Django", "Flask", "FastAPI", "JavaScript", "TypeScript",
                "React", "Angular", "Vue", "Node.js", "Next.js", "HTML", "CSS", "Tailwind", "Go", "Golang", "Rust", "Kotlin", "Swift", "Ruby",
                "Rails", "PHP", "Laravel", "C++", "C#", ".NET", "Scala", "SQL", "PostgreSQL", "MySQL", "MongoDB", "Redis", "Cassandra",
                "Elasticsearch", "Kafka", "RabbitMQ", "Spark", "Hadoop", "Airflow", "dbt", "Snowflake", "Tableau", "Power BI", "Pandas",
                "TensorFlow", "PyTorch", "LLM", "AWS", "Azure", "GCP", "Docker", "Kubernetes", "Terraform", "Ansible", "Jenkins", "CI/CD",
                "Linux", "Git", "GraphQL", "REST", "gRPC", "Microservices", "Selenium", "Playwright", "Cypress", "Appium", "JMeter", "Postman",
                "TCP/IP", "BGP", "OSPF", "CCNA", "Figma")) {
            // Word-style lookarounds, because \b does not work next to symbols such as the ones in C++, C# and .NET.
            SKILLS.put(skill, Pattern.compile("(?<![\\w.+#])" + Pattern.quote(skill) + "(?![\\w+#])",
                    skill.equals("Go") || skill.equals("Swift") || skill.equals("Rust") || skill.equals("Spark") || skill.equals("REST") ? 0 : I));
        }
    }

    private static final Pattern REMOTE = Pattern.compile("\\b(fully remote|100% remote|remote[- ]first|work from home|wfh|remote)\\b", I);
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b", I);
    private static final Pattern ONSITE = Pattern.compile("\\b(on[- ]?site|in[- ]office|work from office|wfo)\\b", I);
    private static final Pattern DESCRIPTION_REMOTE = Pattern.compile("\\b(fully remote|100% remote|remote[- ]first|work from home)\\b", I);
    private static final Pattern DESCRIPTION_ONSITE = Pattern.compile("\\b(work from office|fully on[- ]?site|100% on[- ]?site)\\b", I);

    private JobAttributes() {}

    public static RoleCategory roleCategory(String title) {
        if (title != null) {
            for (Map.Entry<RoleCategory, Pattern> role : ROLES.entrySet()) {
                if (role.getValue().matcher(title).find()) {
                    return role.getKey();
                }
            }
        }
        return RoleCategory.OTHER;
    }

    public static WorkMode workMode(String hint, String location, String title, String description) {
        WorkMode fromHint = fromShortText(hint);
        if (fromHint != WorkMode.UNKNOWN) {
            return fromHint;
        }
        WorkMode fromLocation = fromShortText(location);
        if (fromLocation != WorkMode.UNKNOWN) {
            return fromLocation;
        }
        WorkMode fromTitle = fromShortText(title);
        if (fromTitle != WorkMode.UNKNOWN) {
            return fromTitle;
        }
        // Long text mentions "remote" and "hybrid" in passing, so only firm phrases count here.
        if (description != null) {
            if (HYBRID.matcher(description).find() && !DESCRIPTION_REMOTE.matcher(description).find()) {
                return WorkMode.HYBRID;
            }
            if (DESCRIPTION_REMOTE.matcher(description).find()) {
                return WorkMode.REMOTE;
            }
            if (DESCRIPTION_ONSITE.matcher(description).find()) {
                return WorkMode.ONSITE;
            }
        }
        return WorkMode.UNKNOWN;
    }

    private static WorkMode fromShortText(String text) {
        if (text == null) {
            return WorkMode.UNKNOWN;
        }
        if (HYBRID.matcher(text).find()) {
            return WorkMode.HYBRID;
        }
        if (REMOTE.matcher(text).find()) {
            return WorkMode.REMOTE;
        }
        return ONSITE.matcher(text).find() ? WorkMode.ONSITE : WorkMode.UNKNOWN;
    }

    public static String skills(String title, String description) {
        String text = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        List<String> found = new ArrayList<>();
        SKILLS.forEach((name, pattern) -> {
            if (pattern.matcher(text).find()) {
                found.add(name);
            }
        });
        return String.join(", ", found);
    }

    /** Keywords from the list that appear in the job, whole-word and case-insensitive. */
    public static List<String> matchedKeywords(List<String> keywords, String title, String skills, String description) {
        String text = String.join("\n", title == null ? "" : title, skills == null ? "" : skills, description == null ? "" : description);
        return keywords.stream()
                .filter(k -> Pattern.compile("(?<![\\w.+#])" + Pattern.quote(k) + "(?![\\w+#])", I).matcher(text).find())
                .toList();
    }

    /** Percentage of profile keywords found in the job; 0 if the profile has none. */
    public static int matchScore(List<String> keywords, List<String> matched) {
        return keywords.isEmpty() ? 0 : Math.round(100f * matched.size() / keywords.size());
    }
}
