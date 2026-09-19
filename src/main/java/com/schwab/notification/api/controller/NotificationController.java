package com.schwab.notification.api.controller;

import com.schwab.notification.api.dto.NotificationRequestDto;
import com.schwab.notification.api.dto.NotificationResponseDto;
import com.schwab.notification.service.IngestionResult;
import com.schwab.notification.service.NotificationIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationIngestionService ingestionService;
    private final com.schwab.notification.service.NotificationQueryService queryService;

    @PostMapping
    public ResponseEntity<NotificationResponseDto> submitNotification(@Valid @RequestBody NotificationRequestDto request) {
        log.info("Received notification submission from sourceSystem='{}', eventId='{}', idempotencyKey='{}'",
                request.getSourceSystem(), request.getEventId(), request.getIdempotencyKey());

        IngestionResult result = ingestionService.ingestNotification(request);

        if (result.isDuplicate()) {
            log.info("Returning existing notification HTTP 200 OK for duplicate idempotencyKey='{}'",
                    request.getIdempotencyKey());
            return ResponseEntity.status(HttpStatus.OK).body(result.getNotification());
        }

        log.info("Returning HTTP 202 Accepted for new notificationId='{}'",
                result.getNotification().getNotificationId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.getNotification());
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public ResponseEntity<com.schwab.notification.api.dto.NotificationStatusResponseDto> getNotificationStatus(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        log.info("Querying notification status for id='{}'", id);
        com.schwab.notification.api.dto.NotificationStatusResponseDto response = queryService.getNotificationStatus(id);
        return ResponseEntity.ok(response);
    }
}

