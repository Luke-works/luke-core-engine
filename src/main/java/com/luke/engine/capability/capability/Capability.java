package com.luke.engine.capability.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A platform capability (a feature/module that a tenant can subscribe to),
 * e.g. CALENDAR, SLA. The shape mirrors what luke-core-ui's sidebar consumes
 * from /api/my-subscriptions ({ code, name, icon, route, status, tier }).
 *
 * Lives in the capability engine's own schema (see hibernate.default_schema).
 */
@Entity
@Table(name = "luke_capabilities")
public class Capability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Stable machine code, e.g. "CALENDAR", "SLA". */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    /** Icon key the UI renders (lucide name, etc.). */
    private String icon;

    /** Front-end route this capability unlocks, e.g. "/calendars". */
    private String route;

    /** Lifecycle status: ACTIVE, INACTIVE, BETA. */
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** Pricing/availability tier: FREE, STANDARD, PREMIUM. */
    @Column(nullable = false)
    private String tier = "STANDARD";

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public Capability() {}

    public Capability(String code, String name, String description, String icon, String route, String status, String tier) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.route = route;
        this.status = status;
        this.tier = tier;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
