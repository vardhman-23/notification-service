package com.schwab.notification.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.schwab.notification.domain.types.ChannelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "notification_recipients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    @JsonIgnore
    private Notification notification;

    @Column(name = "recipient_id", length = 100, nullable = false)
    private String recipientId;

    @Column(name = "destination", length = 255, nullable = false)
    private String destination;

    @Column(name = "preferred_channels", length = 255)
    private String preferredChannels;

    @Column(name = "opted_out_channels", length = 255)
    private String optedOutChannels;

    @Column(name = "quiet_hours_start")
    private Integer quietHoursStart;

    @Column(name = "quiet_hours_end")
    private Integer quietHoursEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public boolean isChannelOptedOut(ChannelType channel) {
        if (optedOutChannels == null || optedOutChannels.trim().isEmpty()) {
            return false;
        }
        return Arrays.stream(optedOutChannels.split(","))
                .map(String::trim)
                .anyMatch(opt -> opt.equalsIgnoreCase(channel.name()));
    }

    public boolean isInQuietHours(int currentHour) {
        if (quietHoursStart == null || quietHoursEnd == null) {
            return false;
        }
        if (quietHoursStart > quietHoursEnd) {
            // e.g. 22:00 to 07:00 (spans midnight)
            return currentHour >= quietHoursStart || currentHour < quietHoursEnd;
        } else if (quietHoursStart < quietHoursEnd) {
            // e.g. 01:00 to 06:00
            return currentHour >= quietHoursStart && currentHour < quietHoursEnd;
        } else {
            return false;
        }
    }
}
