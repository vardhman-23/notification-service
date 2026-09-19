package com.demo.notification.domain.repository;

import com.demo.notification.domain.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByNotificationIdOrderByTimestampAsc(UUID notificationId);
}
