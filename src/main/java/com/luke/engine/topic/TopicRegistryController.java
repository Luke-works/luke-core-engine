package com.luke.engine.topic;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/topics")
public class TopicRegistryController {

    private final RegisteredTopicRepository repository;

    public TopicRegistryController(RegisteredTopicRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<RegisteredTopic> getAll() {
        return repository.findAllByOrderByTopicNameAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RegisteredTopic> getById(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody RegisteredTopic body) {
        if (body.getTopicName() == null || body.getTopicName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "topicName is required"));
        }

        if (repository.existsByTopicName(body.getTopicName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Topic '" + body.getTopicName() + "' is already registered"));
        }

        RegisteredTopic topic = new RegisteredTopic();
        topic.setTopicName(body.getTopicName());
        topic.setDescription(body.getDescription());
        topic.setCreatedBy(body.getCreatedBy());
        topic.setWorkerType(body.getWorkerType() != null ? body.getWorkerType() : "JAVA");
        topic.setWorkerClass(body.getWorkerClass());
        topic.setWorkerName(body.getWorkerName());
        topic.setPollType(body.getPollType() != null ? body.getPollType() : "LONG_POLLING");
        topic.setPollIntervalMs(body.getPollIntervalMs());
        topic.setLockDurationMs(body.getLockDurationMs() != null ? body.getLockDurationMs() : 300000L);
        topic.setAutoExtendLock(body.isAutoExtendLock());
        topic.setMaxTasksPerPoll(body.getMaxTasksPerPoll() != null ? body.getMaxTasksPerPoll() : 1);
        topic.setRetries(body.getRetries() != null ? body.getRetries() : 3);
        topic.setRetryDelayMs(body.getRetryDelayMs());
        topic.setRetryBackoff(body.getRetryBackoff() != null ? body.getRetryBackoff() : "FIXED");
        topic.setResolutionPaths(body.getResolutionPaths() != null ? body.getResolutionPaths() : "RETRY,EXTEND_LOCK,MANUAL");
        topic.setAutoRetryOnFailure(body.isAutoRetryOnFailure());
        topic.setStaleLockTimeoutMs(body.getStaleLockTimeoutMs());

        repository.save(topic);
        return ResponseEntity.status(HttpStatus.CREATED).body(topic);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody RegisteredTopic body) {
        return repository.findById(id).map(topic -> {
            if (body.getDescription() != null) topic.setDescription(body.getDescription());
            if (body.getWorkerType() != null) topic.setWorkerType(body.getWorkerType());
            if (body.getWorkerClass() != null) topic.setWorkerClass(body.getWorkerClass());
            if (body.getWorkerName() != null) topic.setWorkerName(body.getWorkerName());
            if (body.getPollType() != null) topic.setPollType(body.getPollType());
            if (body.getPollIntervalMs() != null) topic.setPollIntervalMs(body.getPollIntervalMs());
            if (body.getLockDurationMs() != null) topic.setLockDurationMs(body.getLockDurationMs());
            topic.setAutoExtendLock(body.isAutoExtendLock());
            if (body.getMaxTasksPerPoll() != null) topic.setMaxTasksPerPoll(body.getMaxTasksPerPoll());
            if (body.getRetries() != null) topic.setRetries(body.getRetries());
            if (body.getRetryDelayMs() != null) topic.setRetryDelayMs(body.getRetryDelayMs());
            if (body.getRetryBackoff() != null) topic.setRetryBackoff(body.getRetryBackoff());
            if (body.getResolutionPaths() != null) topic.setResolutionPaths(body.getResolutionPaths());
            topic.setAutoRetryOnFailure(body.isAutoRetryOnFailure());
            if (body.getStaleLockTimeoutMs() != null) topic.setStaleLockTimeoutMs(body.getStaleLockTimeoutMs());
            topic.setActive(body.isActive());
            repository.save(topic);
            return ResponseEntity.ok(topic);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Boolean>> validate(@RequestParam String topicName) {
        boolean registered = repository.findByTopicName(topicName)
                .map(RegisteredTopic::isActive)
                .orElse(false);
        return ResponseEntity.ok(Map.of("registered", registered));
    }
}