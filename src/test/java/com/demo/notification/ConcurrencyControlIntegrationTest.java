package com.demo.notification;

import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.RecipientRequestDto;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.service.IngestionResult;
import com.demo.notification.service.NotificationIngestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyControlIntegrationTest {

    @Autowired
    private NotificationIngestionService ingestionService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("Concurrency Control: Simultaneous identical requests resolve cleanly via unique DB constraint")
    void testConcurrentIdenticalSubmissions_HandledWithoutDuplicateRows() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        String commonEventId = "concurrent-evt-" + UUID.randomUUID();
        String commonIdempotencyKey = "concurrent-idem-" + UUID.randomUUID();

        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("concurrent-trading-engine")
                .eventId(commonEventId)
                .idempotencyKey(commonIdempotencyKey)
                .notificationType("ORDER_EXECUTED")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .subject("Concurrent Order Executed")
                .body("Order execution confirmation under high concurrency race condition.")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("trader_race_1")
                                .destination("trader_race@example.com")
                                .preferredChannels("EMAIL")
                                .build()
                ))
                .build();

        List<Callable<IngestionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                // Await simultaneous trigger release
                startLatch.await();
                return ingestionService.ingestNotification(request);
            });
        }

        List<Future<IngestionResult>> futures = new ArrayList<>();
        for (Callable<IngestionResult> task : tasks) {
            futures.add(executor.submit(task));
        }

        // Wait for all threads to align at the starting gate
        boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
        assertThat(ready).isTrue();

        // Release all threads simultaneously to trigger a race condition
        startLatch.countDown();

        List<IngestionResult> results = new ArrayList<>();
        for (Future<IngestionResult> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }

        executor.shutdown();

        // Exactly one submission must be the original accepted, all other threads must be duplicates
        long acceptedCount = results.stream().filter(r -> !r.isDuplicate()).count();
        long duplicateCount = results.stream().filter(IngestionResult::isDuplicate).count();

        assertThat(acceptedCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(threadCount - 1);

        // All results must reference the identical persistent notification ID
        UUID primaryId = results.get(0).getNotification().getNotificationId();
        assertThat(results).allMatch(r -> r.getNotification().getNotificationId().equals(primaryId));

        // Verify that exactly ONE row exists in the database for this composite key
        List<Notification> databaseRows = notificationRepository.findAll().stream()
                .filter(n -> "concurrent-trading-engine".equals(n.getSourceSystem()) && commonEventId.equals(n.getEventId()))
                .toList();

        assertThat(databaseRows).hasSize(1);
        assertThat(databaseRows.get(0).getIdempotencyKey()).isEqualTo(commonIdempotencyKey);
    }
}

