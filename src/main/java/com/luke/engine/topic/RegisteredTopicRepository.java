package com.luke.engine.topic;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface RegisteredTopicRepository extends JpaRepository<RegisteredTopic, String> {
    Optional<RegisteredTopic> findByTopicName(String topicName);
    boolean existsByTopicName(String topicName);
    List<RegisteredTopic> findByActiveTrue();
    List<RegisteredTopic> findAllByOrderByTopicNameAsc();
}