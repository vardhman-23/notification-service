package com.demo.notification.api.controller;

import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.NotificationResponseDto;
import com.demo.notification.api.dto.NotificationStatusResponseDto;
import com.demo.notification.api.error.ApiErrorResponse;
import com.demo.notification.delivery.AsyncNotificationPipeline;
import com.demo.notification.service.IngestionResult;
import com.demo.notification.service.NotificationIngestionService;
import com.demo.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * REST Controller exposing endpoints for notification submission, aggregate status tracking,
 * and manual on-demand delivery dispatch.
 */
@RestController
@RequestMapping(path = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Notifications", description = "Endpoints for alert submission, status querying, and delivery dispatch")
public class NotificationController {

    private final NotificationIngestionService ingestionService;
    private final NotificationQueryService queryService;
    private final AsyncNotificationPipeline asyncPipeline;

    /**
     * Submits an alert or notification for ingestion, validation, deduplication, and asynchronous delivery.
     *
     * @param request the validated notification request payload
     * @return {@code 202 Accepted} with {@code Location} header if newly accepted, or {@code 200 OK} if duplicate
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Submit an alert or notification",
            description = "Ingests a notification request. Evaluates composite idempotency (sourceSystem + eventId + idempotencyKey). " +
                    "Returns 202 Accepted with a Location header for new submissions, or 200 OK for duplicate resubmissions."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "202",
                    description = "Notification accepted for asynchronous routing and delivery",
                    content = @Content(schema = @Schema(implementation = NotificationResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "200",
                    description = "Duplicate submission detected; returning existing record without re-dispatching",
                    content = @Content(schema = @Schema(implementation = NotificationResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid payload or validation constraint failure",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<NotificationResponseDto> submitNotification(@Valid @RequestBody NotificationRequestDto request) {
        log.info("Received notification submission from sourceSystem='{}', eventId='{}', idempotencyKey='{}'",
                request.getSourceSystem(), request.getEventId(), request.getIdempotencyKey());

        IngestionResult result = ingestionService.ingestNotification(request);

        if (result.isDuplicate()) {
            log.info("Duplicate submission detected for idempotencyKey='{}'. Returning existing notification HTTP 200 OK",
                    request.getIdempotencyKey());
            return ResponseEntity.ok(result.getNotification());
        }

        URI location = URI.create("/api/v1/notifications/" + result.getNotification().getNotificationId());
        log.info("Returning HTTP 202 Accepted with Location='{}' for notificationId='{}'",
                location, result.getNotification().getNotificationId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(result.getNotification());
    }

    /**
     * Retrieves the delivery progress, aggregate status, and chronological audit history of a notification.
     *
     * @param id unique UUID of the notification
     * @return comprehensive status response DTO
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Query notification status and audit timeline",
            description = "Retrieves the current aggregate status, individual channel attempt progress, metrics summary, and audit events."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Notification status and audit trail retrieved successfully",
                    content = @Content(schema = @Schema(implementation = NotificationStatusResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Notification not found for the provided UUID",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid UUID format in path variable",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<NotificationStatusResponseDto> getNotificationStatus(
            @Parameter(description = "UUID of the notification", required = true)
            @PathVariable UUID id) {
        log.debug("Querying notification status for id='{}'", id);
        NotificationStatusResponseDto response = queryService.getNotificationStatus(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Manually triggers the routing and delivery pipeline for an accepted notification.
     *
     * @param id unique UUID of the notification
     * @return updated notification status response DTO
     */
    @PostMapping("/{id}/dispatch")
    @Operation(
            summary = "Manually trigger routing and delivery pipeline",
            description = "Idempotently executes channel resolution and delivery dispatch for a notification."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Pipeline execution completed, updated status returned",
                    content = @Content(schema = @Schema(implementation = NotificationStatusResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Notification not found",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    public ResponseEntity<NotificationStatusResponseDto> dispatchNotification(
            @Parameter(description = "UUID of the notification to dispatch", required = true)
            @PathVariable UUID id) {
        log.info("Explicitly triggering dispatch pipeline for notificationId='{}'", id);
        asyncPipeline.executePipeline(id);
        NotificationStatusResponseDto response = queryService.getNotificationStatus(id);
        return ResponseEntity.ok(response);
    }
}
