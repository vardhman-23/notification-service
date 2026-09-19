package com.demo.notification.domain.repository;

import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.types.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    List<DeliveryAttempt> findByNotificationNotificationId(UUID notificationId);
    List<DeliveryAttempt> findByStatus(DeliveryStatus status);
}
