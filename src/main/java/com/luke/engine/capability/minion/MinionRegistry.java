package com.luke.engine.capability.minion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Collects every {@link Minion} bean by name, so the controllers can dispatch by the operation the
 *  form referenced. Built once at startup from the application context. */
@Component
public class MinionRegistry {

    private final Map<String, Minion> byName;

    public MinionRegistry(List<Minion> minions) {
        Map<String, Minion> m = new HashMap<>();
        for (Minion minion : minions) {
            m.put(minion.name(), minion);
        }
        this.byName = Map.copyOf(m);
    }

    /** The minion for this operation name, or {@code null} if none is registered. */
    public Minion get(String name) {
        return name == null ? null : byName.get(name);
    }
}
