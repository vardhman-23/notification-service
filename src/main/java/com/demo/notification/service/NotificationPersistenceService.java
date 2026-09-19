package com.demo.notification.service;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated transactional persistence boundary for notifications.
 * <p>
 * Uses {@link Propagation#REQUIRES_NEW} to ensure that race condition collisions
 * resulting in database unique constraint violations roll back cleanly in their own transaction
 * without poisoning the caller's transaction context.
 */
@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {

    private final NotificationRepository notificationRepository;

    /**
     * Persists and flushes a notification aggregate in an isolated transaction.
     *
     * @param notification the notification entity to save and flush
     * @return persisted notification aggregate
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification saveAndFlush(Notification notification) {
        return notificationRepository.saveAndFlush(notification);
    }
}

