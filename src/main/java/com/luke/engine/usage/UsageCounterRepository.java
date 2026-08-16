package com.luke.engine.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Store for {@link UsageCounter} tallies. The {@link #increment} is a keyed atomic bump (portable
 * JPQL — works on H2 and Postgres, unlike a native {@code ON CONFLICT}); a return of 0 means the
 * period row does not exist yet and the caller creates it.
 */
public interface UsageCounterRepository extends JpaRepository<UsageCounter, String> {

    /** Atomically add one to an existing period row. Returns rows affected (0 = no such row yet). */
    @Modifying
    @Query("update UsageCounter u set u.count = u.count + 1, u.updatedAt = CURRENT_TIMESTAMP where u.id = :id")
    int increment(@Param("id") String id);
}
