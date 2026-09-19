package com.schwab.notification.domain.repository;

import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.types.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);
    Optional<Notification> findBySourceSystemAndEventIdAndIdempotencyKey(String sourceSystem, String eventId, String idempotencyKey);
    List<Notification> findByEventId(String eventId);
    List<Notification> findByStatus(NotificationStatus status);
}
