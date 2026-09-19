package com.demo.notification.service;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPersistenceServiceUnitTest {

    @Test
    @DisplayName("saveAndFlush delegates directly to NotificationRepository.saveAndFlush")
    void testSaveAndFlush() {
        NotificationRepository repository = mock(NotificationRepository.class);
        NotificationPersistenceService service = new NotificationPersistenceService(repository);

        Notification notification = Notification.builder().build();
        when(repository.saveAndFlush(notification)).thenReturn(notification);

        Notification result = service.saveAndFlush(notification);

        assertThat(result).isSameAs(notification);
        verify(repository).saveAndFlush(notification);
    }
}

