package com.demo.notification.domain.model;

import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root representing a notification or alert request in the system.
 * <p>
 * Maintains the composite uniqueness constraint on ({@code source_system}, {@code event_id}, {@code idempotency_key}),
 * the aggregate lifecycle state, payload details, and associated recipient and delivery attempt entities.
 */
@Entity
@Table(
    name = "notifications",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_idempotency",
            columnNames = {"source_system", "event_id", "idempotency_key"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "source_system", length = 100, nullable = false)
    private String sourceSystem;

    @Column(name = "event_id", length = 100, nullable = false)
    private String eventId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "notification_type", length = 50, nullable = false)
    private String notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20, nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20, nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private NotificationStatus status;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<NotificationRecipient> recipients = new ArrayList<>();

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DeliveryAttempt> deliveryAttempts = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.notificationId == null) {
            this.notificationId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = NotificationStatus.ACCEPTED;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Associates a recipient with this notification aggregate.
     *
     * @param recipient recipient entity
     */
    public void addRecipient(NotificationRecipient recipient) {
        if (this.recipients == null) {
            this.recipients = new ArrayList<>();
        }
        this.recipients.add(recipient);
        recipient.setNotification(this);
    }

    /**
     * Records a delivery attempt associated with this notification aggregate.
     *
     * @param attempt delivery attempt entity
     */
    public void addDeliveryAttempt(DeliveryAttempt attempt) {
        if (this.deliveryAttempts == null) {
            this.deliveryAttempts = new ArrayList<>();
        }
        this.deliveryAttempts.add(attempt);
        attempt.setNotification(this);
    }
}
