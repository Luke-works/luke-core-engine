package com.luke.engine.topic;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "luke_registered_topics")
public class RegisteredTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /* ── Identity ────────────────────────────────────────────── */

    @Column(nullable = false, unique = true)
    private String topicName;

    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /* ── Worker Configuration ────────────────────────────────── */

    /** Language of the worker: JAVA, PYTHON, JAVASCRIPT, GROOVY */
    @Column(nullable = false)
    private String workerType = "JAVA";

    /** Fully qualified class / module / script path of the worker */
    private String workerClass;

    /** Identifier of the worker application / service */
    private String workerName;

    /* ── Polling Configuration ───────────────────────────────── */

    /** How the worker polls: LONG_POLLING, INTERVAL, PUSH */
    @Column(nullable = false)
    private String pollType = "LONG_POLLING";

    /** Poll interval in milliseconds (for INTERVAL type) */
    private Long pollIntervalMs;

    /** Lock duration in milliseconds — how long the worker holds the task */
    @Column(nullable = false)
    private Long lockDurationMs = 300000L; // 5 minutes default

    /** Whether to automatically extend the lock before it expires */
    @Column(nullable = false)
    private boolean autoExtendLock = false;

    /** Max number of tasks fetched per poll */
    @Column(nullable = false)
    private Integer maxTasksPerPoll = 1;

    /* ── Retry & Error Strategy ──────────────────────────────── */

    /** Number of retries before the task is marked as failed incident */
    @Column(nullable = false)
    private Integer retries = 3;

    /** Delay between retries in milliseconds */
    private Long retryDelayMs;

    /** Retry backoff strategy: FIXED, LINEAR, EXPONENTIAL */
    @Column(nullable = false)
    private String retryBackoff = "FIXED";

    /* ── Resolution Paths (when task fails) ───────────────────── */

    /** Comma-separated resolution options: RETRY,EXTEND_LOCK,CANCEL,SKIP,MANUAL */
    @Column(nullable = false)
    private String resolutionPaths = "RETRY,EXTEND_LOCK,MANUAL";

    /** Whether to auto-retry on failure (vs requiring manual intervention) */
    @Column(nullable = false)
    private boolean autoRetryOnFailure = true;

    /** Timeout in ms after which an unreleased lock is considered stale */
    private Long staleLockTimeoutMs;

    /* ── Metadata ────────────────────────────────────────────── */

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private String createdBy;

    private LocalDateTime updatedAt;

    /* ── Constructors ────────────────────────────────────────── */

    public RegisteredTopic() {}

    public RegisteredTopic(String topicName, String description, String createdBy) {
        this.topicName = topicName;
        this.description = description;
        this.createdBy = createdBy;
    }

    /* ── Getters / Setters ───────────────────────────────────── */

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getWorkerType() { return workerType; }
    public void setWorkerType(String workerType) { this.workerType = workerType; }

    public String getWorkerClass() { return workerClass; }
    public void setWorkerClass(String workerClass) { this.workerClass = workerClass; }

    public String getWorkerName() { return workerName; }
    public void setWorkerName(String workerName) { this.workerName = workerName; }

    public String getPollType() { return pollType; }
    public void setPollType(String pollType) { this.pollType = pollType; }

    public Long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(Long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public Long getLockDurationMs() { return lockDurationMs; }
    public void setLockDurationMs(Long lockDurationMs) { this.lockDurationMs = lockDurationMs; }

    public boolean isAutoExtendLock() { return autoExtendLock; }
    public void setAutoExtendLock(boolean autoExtendLock) { this.autoExtendLock = autoExtendLock; }

    public Integer getMaxTasksPerPoll() { return maxTasksPerPoll; }
    public void setMaxTasksPerPoll(Integer maxTasksPerPoll) { this.maxTasksPerPoll = maxTasksPerPoll; }

    public Integer getRetries() { return retries; }
    public void setRetries(Integer retries) { this.retries = retries; }

    public Long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(Long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    public String getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(String retryBackoff) { this.retryBackoff = retryBackoff; }

    public String getResolutionPaths() { return resolutionPaths; }
    public void setResolutionPaths(String resolutionPaths) { this.resolutionPaths = resolutionPaths; }

    public boolean isAutoRetryOnFailure() { return autoRetryOnFailure; }
    public void setAutoRetryOnFailure(boolean autoRetryOnFailure) { this.autoRetryOnFailure = autoRetryOnFailure; }

    public Long getStaleLockTimeoutMs() { return staleLockTimeoutMs; }
    public void setStaleLockTimeoutMs(Long staleLockTimeoutMs) { this.staleLockTimeoutMs = staleLockTimeoutMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}