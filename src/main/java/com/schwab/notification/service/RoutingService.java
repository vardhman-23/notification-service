package com.schwab.notification.service;

import com.schwab.notification.channel.ChannelProvider;
import com.schwab.notification.channel.ChannelProviderRegistry;
import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.DeliveryAttempt;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingService {

    private final ChannelProviderRegistry channelProviderRegistry;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public RoutingResult routeNotification(Notification notification) {
        return routeNotification(notification, LocalTime.now().getHour());
    }

    @Transactional
    public RoutingResult routeNotification(Notification notification, int currentHour) {
        log.info("Executing routing for notificationId='{}', severity='{}', evaluationHour={}:00",
                notification.getNotificationId(), notification.getSeverity(), currentHour);

        Map<String, Set<ChannelType>> routingMap = new HashMap<>();

        for (NotificationRecipient recipient : notification.getRecipients()) {
            Set<ChannelType> selectedChannels = resolveChannelsWithIntelligentFallback(
                    notification, recipient, currentHour);

            // Filter to only available channels
            Set<ChannelType> availableChannels = selectedChannels.stream()
                    .filter(channelProviderRegistry::isChannelAvailable)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ChannelType.class)));

            if (availableChannels.isEmpty()) {
                log.warn("No available channel found for recipientId='{}', falling back to EMAIL", recipient.getRecipientId());
                availableChannels = EnumSet.of(ChannelType.EMAIL);
            }

            routingMap.put(recipient.getRecipientId(), availableChannels);

            // Create staged delivery attempts for each resolved channel
            for (ChannelType channel : availableChannels) {
                boolean attemptExists = notification.getDeliveryAttempts().stream()
                        .anyMatch(da -> da.getRecipientId().equals(recipient.getRecipientId()) && da.getChannel() == channel);

                if (!attemptExists) {
                    String providerName = channelProviderRegistry.getProvider(channel)
                            .map(ChannelProvider::getProviderName)
                            .orElse("DEFAULT_PROVIDER");

                    DeliveryAttempt attempt = DeliveryAttempt.builder()
                            .recipientId(recipient.getRecipientId())
                            .channel(channel)
                            .provider(providerName)
                            .status(DeliveryStatus.PENDING)
                            .attemptNumber(1)
                            .build();

                    notification.addDeliveryAttempt(attempt);
                }
            }

            // Log standard routing completion in AuditLog
            String routingReason = notification.getSeverity() == Severity.CRITICAL
                    ? String.format("Severity CRITICAL forced channels %s for recipient '%s'", availableChannels, recipient.getRecipientId())
                    : String.format("Routing completed for recipient '%s'. Final channels: %s", recipient.getRecipientId(), availableChannels);

            AuditLog routingAudit = AuditLog.builder()
                    .notificationId(notification.getNotificationId())
                    .action(AuditAction.ROUTED)
                    .metadataReason(routingReason)
                    .sanitizedPayloadSummary(String.format("Recipient: %s, Channels: %s", recipient.getRecipientId(), availableChannels))
                    .timestamp(Instant.now())
                    .build();

            auditLogRepository.save(routingAudit);
        }

        notification.setStatus(NotificationStatus.ROUTED);
        Notification updatedNotification = notificationRepository.save(notification);

        log.info("Routing completed for notificationId='{}', total recipients routed: {}",
                updatedNotification.getNotificationId(), routingMap.size());

        return new RoutingResult(updatedNotification, routingMap);
    }

    /**
     * Hierarchical Intelligent Fallback Engine as mandated by ADR-001:
     * - Tier 1: Regulatory Override (CRITICAL forces EMAIL + SMS, bypassing opt-outs and quiet hours)
     * - Tier 2: Quiet Hours Diversion (Non-critical intrusive SMS diverted to EMAIL)
     * - Tier 3: Opt-Out Fallback (Non-critical opted-out channel diverted to EMAIL)
     */
    private Set<ChannelType> resolveChannelsWithIntelligentFallback(Notification notification,
                                                                    NotificationRecipient recipient,
                                                                    int currentHour) {
        // --- Tier 1: Regulatory / Emergency Override ---
        if (notification.getSeverity() == Severity.CRITICAL) {
            boolean hadOptOut = recipient.isChannelOptedOut(ChannelType.SMS);
            boolean inQuietHours = recipient.isInQuietHours(currentHour);

            if (hadOptOut || inQuietHours) {
                log.warn("Regulatory override applied for CRITICAL notification '{}' to recipient '{}' (OptOut={}, QuietHours={})",
                        notification.getNotificationId(), recipient.getRecipientId(), hadOptOut, inQuietHours);

                AuditLog overrideLog = AuditLog.builder()
                        .notificationId(notification.getNotificationId())
                        .action(AuditAction.REGULATORY_OVERRIDE_APPLIED)
                        .metadataReason(String.format(
                                "FINRA/SEC duty-of-care override applied for CRITICAL alert. Overriding user preferences (OptOut=%s, QuietHours=%s) to enforce mandatory SMS+EMAIL.",
                                hadOptOut, inQuietHours))
                        .sanitizedPayloadSummary(String.format("Recipient: %s, ForcedChannels: [EMAIL, SMS]", recipient.getRecipientId()))
                        .timestamp(Instant.now())
                        .build();
                auditLogRepository.save(overrideLog);
            }

            return EnumSet.of(ChannelType.EMAIL, ChannelType.SMS);
        }

        // --- Determine Candidate Channels ---
        Set<ChannelType> candidateChannels = parsePreferredChannels(recipient);
        Set<ChannelType> resolvedChannels = new LinkedHashSet<>();

        for (ChannelType candidate : candidateChannels) {
            // --- Tier 3: User Opt-Out Check ---
            if (recipient.isChannelOptedOut(candidate)) {
                log.info("Recipient '{}' opted out of channel '{}'. Applying Intelligent Fallback to EMAIL.",
                        recipient.getRecipientId(), candidate);

                resolvedChannels.add(ChannelType.EMAIL);

                AuditLog fallbackLog = AuditLog.builder()
                        .notificationId(notification.getNotificationId())
                        .action(AuditAction.CHANNEL_FALLBACK_APPLIED)
                        .metadataReason(String.format(
                                "User opted out of requested channel '%s'. Applied intelligent fallback to EMAIL.", candidate))
                        .sanitizedPayloadSummary(String.format("Recipient: %s, Fallback: %s -> EMAIL", recipient.getRecipientId(), candidate))
                        .timestamp(Instant.now())
                        .build();
                auditLogRepository.save(fallbackLog);
                continue;
            }

            // --- Tier 2: Quiet-Hours Check ---
            if (candidate == ChannelType.SMS && recipient.isInQuietHours(currentHour)) {
                log.info("Recipient '{}' in quiet hours ({}:00). Diverting intrusive SMS to EMAIL.",
                        recipient.getRecipientId(), currentHour);

                resolvedChannels.add(ChannelType.EMAIL);

                AuditLog quietLog = AuditLog.builder()
                        .notificationId(notification.getNotificationId())
                        .action(AuditAction.QUIET_HOURS_FALLBACK)
                        .metadataReason(String.format(
                                "Quiet-hour restriction active at %02d:00 (Window: %02d:00-%02d:00). Diverted intrusive SMS to EMAIL.",
                                currentHour, recipient.getQuietHoursStart(), recipient.getQuietHoursEnd()))
                        .sanitizedPayloadSummary(String.format("Recipient: %s, QuietHours Divert: SMS -> EMAIL", recipient.getRecipientId()))
                        .timestamp(Instant.now())
                        .build();
                auditLogRepository.save(quietLog);
                continue;
            }

            // Standard routing
            resolvedChannels.add(candidate);
        }

        return resolvedChannels.isEmpty() ? EnumSet.of(ChannelType.EMAIL) : resolvedChannels;
    }

    private Set<ChannelType> parsePreferredChannels(NotificationRecipient recipient) {
        String preferences = recipient.getPreferredChannels();
        if (preferences == null || preferences.trim().isEmpty()) {
            return EnumSet.of(ChannelType.EMAIL);
        }

        Set<ChannelType> channels = Arrays.stream(preferences.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(pref -> {
                    try {
                        return ChannelType.valueOf(pref);
                    } catch (IllegalArgumentException ex) {
                        log.warn("Unknown channel '{}' in preferences, skipping", pref);
                        return null;
                    }
                })
                .filter(ch -> ch != null)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ChannelType.class)));

        return channels.isEmpty() ? EnumSet.of(ChannelType.EMAIL) : channels;
    }
}
