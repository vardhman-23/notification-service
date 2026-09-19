package com.demo.notification.domain.repository;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.types.NotificationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @EntityGraph(attributePaths = {"recipients"})
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"recipients"})
    Optional<Notification> findBySourceSystemAndEventIdAndIdempotencyKey(String sourceSystem, String eventId, String idempotencyKey);

    List<Notification> findByEventId(String eventId);

    List<Notification> findByStatus(NotificationStatus status);
}
