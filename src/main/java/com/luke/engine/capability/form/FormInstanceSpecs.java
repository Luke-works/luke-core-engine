package com.luke.engine.capability.form;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Query construction for the form-instance list (#26): tenant scope plus optional
 * state / definition / submitted-only filters and a free-text search, expressed as a
 * {@link Specification} so the filtering + paging + sorting all happen server-side.
 *
 * <p>Search matches the instance's {@code definitionCode}, {@code id} and
 * {@code createdBy}, and additionally any definition whose friendly <em>name</em>
 * matches — the caller resolves those codes (the name lives on FormDefinition, not on
 * the instance) and passes them in as {@code nameMatchedCodes}.
 */
public final class FormInstanceSpecs {

    /** Sort fields a client may request, mapped to entity attributes (anything else
     *  falls back to createdAt) — a whitelist so arbitrary/injected sorts can't leak. */
    private static final Set<String> SORTABLE = Set.of("createdAt", "submittedAt", "state", "definitionCode");

    private FormInstanceSpecs() {}

    public static Specification<FormInstance> filter(String tenantId, String state, String definitionCode,
            boolean submittedOnly, String search, Collection<String> nameMatchedCodes) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            if (definitionCode != null && !definitionCode.isBlank()) {
                ps.add(cb.equal(root.get("definitionCode"), definitionCode));
            }
            if (submittedOnly) {
                ps.add(root.get("state").in(FormInstanceStates.SUBMITTED_STATES));
            } else if (state != null && !state.isBlank()) {
                ps.add(cb.equal(root.get("state"), state));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                List<Predicate> ors = new ArrayList<>();
                ors.add(cb.like(cb.lower(root.get("definitionCode")), like));
                ors.add(cb.like(cb.lower(root.get("id")), like));
                ors.add(cb.like(cb.lower(cb.coalesce(root.get("createdBy"), "")), like));
                if (nameMatchedCodes != null && !nameMatchedCodes.isEmpty()) {
                    ors.add(root.get("definitionCode").in(nameMatchedCodes));
                }
                ps.add(cb.or(ors.toArray(new Predicate[0])));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** A whitelisted sort; unknown fields fall back to createdAt. Default direction DESC. */
    public static Sort sort(String field, String order) {
        String f = field != null && SORTABLE.contains(field) ? field : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, f);
    }
}
