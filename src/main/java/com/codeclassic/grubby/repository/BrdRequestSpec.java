package com.codeclassic.grubby.repository;

import com.codeclassic.grubby.domain.entity.BrdRequest;
import com.codeclassic.grubby.domain.entity.BrdStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA Specification factory for BrdRequest queries.
 *
 * Using Specifications instead of a raw @Query avoids HQL/JPQL dialect issues
 * (e.g. LOWER() refusing CLOB columns, non-portable CAST syntax) because the
 * Criteria API builds SQL through the JPA metamodel and lets the dialect handle
 * type coercion automatically.
 *
 * Each static method returns a composable Specification that can be combined
 * with Specification.where().and() in the service layer.
 */
public class BrdRequestSpec {

    private BrdRequestSpec() {}

    /**
     * Matches repoUrl (case-insensitive), featureContext, or the numeric id
     * against the given search term using LIKE '%q%'.
     *
     * featureContext is a @Lob / CLOB — the Criteria API handles the type
     * mapping correctly and avoids the LOWER()-on-CLOB error that occurs in HQL.
     */
    public static Specification<BrdRequest> searchMatches(String q) {
        return (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction(); // no-op predicate

            String pattern = "%" + q.toLowerCase() + "%";

            List<Predicate> predicates = new ArrayList<>();

            // repoUrl — VARCHAR, safe to use lower() on
            predicates.add(cb.like(cb.lower(root.get("repoUrl")), pattern));

            // featureContext — CLOB: use plain like() so the driver handles type coercion;
            // MySQL LIKE is case-insensitive by default on utf8_general_ci
            predicates.add(cb.like(root.get("featureContext").as(String.class), "%" + q + "%"));

            // id — cast Long to String for LIKE matching
            predicates.add(cb.like(root.get("id").as(String.class), "%" + q + "%"));

            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Filters by a single exact BrdStatus value.
     * Pass null to skip this filter entirely.
     */
    public static Specification<BrdRequest> hasStatus(BrdStatus status) {
        return (root, query, cb) -> {
            if (status == null) return cb.conjunction(); // no-op predicate
            return cb.equal(root.get("status"), status);
        };
    }

    /**
     * Excludes terminal statuses (COMPLETED and FAILED), effectively filtering
     * for jobs that are still in progress.
     */
    public static Specification<BrdRequest> isRunning() {
        return (root, query, cb) ->
                root.get("status").in(BrdStatus.COMPLETED, BrdStatus.FAILED).not();
    }

    /**
     * P1 — Scopes results to a specific userId.
     * Pass null to skip (admin "view all" scenario).
     */
    public static Specification<BrdRequest> forUser(String userId) {
        return (root, query, cb) -> {
            if (userId == null || userId.isBlank()) return cb.conjunction();
            return cb.equal(root.get("userId"), userId);
        };
    }
}
