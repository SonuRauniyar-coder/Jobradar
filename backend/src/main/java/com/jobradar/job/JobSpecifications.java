package com.jobradar.job;

import com.jobradar.job.JobDtos.JobFilter;
import com.jobradar.job.JobEnums.ApplicationStatus;
import com.jobradar.job.JobEnums.Category;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

final class JobSpecifications {
    private static final List<String> INDIAN_PLACES = List.of("bangalore", "bengaluru", "gurugram", "gurgaon", "noida", "hyderabad",
            "pune", "mumbai", "chennai", "delhi", "kolkata", "ahmedabad", "jaipur", "kochi", "coimbatore", "chandigarh", "indore", "thiruvananthapuram");

    private JobSpecifications() {}

    static Specification<Job> matching(Long userId, JobFilter f, ZoneId zone, Instant firstSeenSince, Sort sort) {
        return (root, query, cb) -> {
            boolean countQuery = Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
            if (!countQuery) {
                root.fetch("company", JoinType.INNER);
                query.orderBy(orders(root, cb, sort));
            }
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("company").get("list").get("userId"), userId));
            all.add(cb.isTrue(root.get("statusOpen")));

            if (f.category() == Category.UNSPECIFIED) {
                all.add(cb.equal(root.get("category"), Category.UNSPECIFIED));
            } else if (f.category() != null) {
                // Unclassified jobs could be either, so they appear under both tabs.
                all.add(root.get("category").in(f.category(), Category.UNSPECIFIED));
            }
            if (StringUtils.hasText(f.location())) {
                all.add(like(cb, root.get("location"), f.location()));
            }
            if (Boolean.TRUE.equals(f.indiaOnly())) {
                all.add(india(cb, root.get("location")));
            }
            if (StringUtils.hasText(f.q())) {
                all.add(cb.or(like(cb, root.get("title"), f.q()), like(cb, root.get("description"), f.q())));
            }
            if (StringUtils.hasText(f.company())) {
                all.add(like(cb, root.get("company").get("name"), f.company()));
            }
            if (f.workMode() != null) {
                all.add(cb.equal(root.get("workMode"), f.workMode()));
            }
            if (f.roleCategory() != null) {
                all.add(cb.equal(root.get("roleCategory"), f.roleCategory()));
            }
            if (f.from() != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("postedAt"), f.from().atStartOfDay(zone).toInstant()));
            }
            if (f.to() != null) {
                all.add(cb.lessThan(root.get("postedAt"), f.to().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (firstSeenSince != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("firstSeenAt"), firstSeenSince));
            }
            all.add(statusPredicate(root, query, cb, userId, f.status()));
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    /** Built here because Spring Data cannot express "nulls last" in Criteria queries, and databases disagree on the default. */
    private static List<Order> orders(Root<Job> root, CriteriaBuilder cb, Sort sort) {
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Path<?> path = root;
            for (String part : order.getProperty().split("\\.")) {
                path = path.get(part);
            }
            orders.add(cb.asc(cb.selectCase().when(cb.isNull(path), 1).otherwise(0)));
            orders.add(order.isDescending() ? cb.desc(path) : cb.asc(path));
        }
        return orders;
    }

    private static Predicate statusPredicate(Root<Job> root, CriteriaQuery<?> query, CriteriaBuilder cb, Long userId, ApplicationStatus status) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<JobUserStatus> s = sub.from(JobUserStatus.class);
        Predicate sameJob = cb.and(cb.equal(s.get("jobId"), root.get("id")), cb.equal(s.get("userId"), userId));
        sub.select(s.get("id"));
        if (status == null) {
            // Ignoring a job hides it everywhere unless the Ignored filter is chosen.
            return cb.not(cb.exists(sub.where(sameJob, cb.equal(s.get("status"), ApplicationStatus.IGNORED))));
        }
        if (status == ApplicationStatus.NEW) {
            return cb.not(cb.exists(sub.where(sameJob, cb.notEqual(s.get("status"), ApplicationStatus.NEW))));
        }
        return cb.exists(sub.where(sameJob, cb.equal(s.get("status"), status)));
    }

    private static Predicate india(CriteriaBuilder cb, Expression<String> location) {
        List<Predicate> any = new ArrayList<>();
        Expression<String> lower = cb.lower(location);
        // "Indiana" and "Indianapolis" contain "india", so that word needs excluding.
        any.add(cb.and(cb.like(lower, "%india%"), cb.notLike(lower, "%indiana%")));
        INDIAN_PLACES.forEach(place -> any.add(cb.like(lower, "%" + place + "%")));
        return cb.or(any.toArray(new Predicate[0]));
    }

    private static Predicate like(CriteriaBuilder cb, Expression<String> field, String value) {
        String escaped = value.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return cb.like(cb.lower(field), "%" + escaped + "%", '\\');
    }
}
