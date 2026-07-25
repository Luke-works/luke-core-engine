package com.luke.engine.capability.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a capability-guarded controller method as requiring a specific {@link CapabilityLevel.Action}
 * that is finer-grained than the default method-derived read/write (#104). Read by
 * {@link CapabilityAccessInterceptor}; an un-annotated method keeps the default (GET/HEAD → READ,
 * everything else → WRITE), so this only ever tightens a route, never loosens it.
 *
 * <p>Use on the privileged/finalizing operations that a {@code contributor} must be blocked from —
 * e.g. {@code publish}, sign-off, seal ({@link CapabilityLevel.Action#PUBLISH}) and irreversible
 * purge ({@link CapabilityLevel.Action#DELETE}). {@code read-write} holders are unaffected (that
 * level permits every action).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresCapabilityAction {
    CapabilityLevel.Action value();
}
