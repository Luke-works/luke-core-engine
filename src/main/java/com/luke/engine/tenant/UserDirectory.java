package com.luke.engine.tenant;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.identity.User;
import org.springframework.stereotype.Component;

/**
 * Resolves engine user ids (the WorkOS {@code sub} stored in created_by / updated_by /
 * audit actor / etc.) to human display names, from Camunda's user store. Ids are the
 * stable system of record; names are looked up here at read time (never denormalized
 * into the domain tables). Batched to avoid N+1.
 */
@Component
public class UserDirectory {

    private final IdentityService identityService;

    public UserDirectory(IdentityService identityService) {
        this.identityService = identityService;
    }

    /** Display name for one user id, or the id itself if unknown. Null in → null out. */
    public String nameFor(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return namesFor(List.of(userId)).getOrDefault(userId, userId);
    }

    /** id → display name for many ids, in ONE query. Unknown ids map to themselves. */
    public Map<String, String> namesFor(Collection<String> userIds) {
        Set<String> ids = userIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (User u : identityService.createUserQuery().userIdIn(ids.toArray(new String[0])).list()) {
            out.put(u.getId(), display(u));
        }
        for (String id : ids) out.putIfAbsent(id, id); // no user row → fall back to the id
        return out;
    }

    private static String display(User u) {
        String name = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        if (!name.isBlank()) return name;
        return u.getEmail() != null && !u.getEmail().isBlank() ? u.getEmail() : u.getId();
    }
}
