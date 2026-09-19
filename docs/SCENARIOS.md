# Assessment Scenarios: Greenfield, Brownfield & Ambiguous Requirements

This document details the three core engineering scenarios mandated by the **Charles Schwab AI-Assisted Software Engineering Assessment**, showcasing **requirement decomposition, execution approach, and empirical validation** for each.

---

## Scenario 1: Greenfield Scenario (Initial Notification Management Capability)

### 1.1 Requirement Decomposition
Design and implement the initial notification management capability from a clean slate:
- **Notification Ingestion & Schema**: Provide `POST /api/v1/notifications` accepting source system, event correlation ID, idempotency key, notification type, severity, priority, recipients, subject, body, scheduling, and expiration.
- **Deduplication Gate**: Enforce composite uniqueness on `(source_system, event_id, idempotency_key)`. Repeated submissions with the same idempotency key must not create a duplicate logical notification, must return `HTTP 200 OK` with the existing entity, and must record `SUPPRESSED_DUPLICATE` in the audit log.
- **Recipient & Channel Selection**: Support recipient-specific destinations (email, phone), preferred channels, and delivery state tracking.
- **Asynchronous Processing**: Decouple submission acceptance (`HTTP 202 Accepted`) from asynchronous channel execution.
- **Status Retrieval API**: Implement `GET /api/v1/notifications/{id}` returning aggregate status, channel-by-channel progress, and an immutable audit timeline.

### 1.2 Execution & Implementation
- **Data Model**: Created JPA entities [`Notification.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/domain/model/Notification.java), [`NotificationRecipient.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/domain/model/NotificationRecipient.java), [`DeliveryAttempt.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/domain/model/DeliveryAttempt.java), and [`AuditLog.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/domain/model/AuditLog.java).
- **Flyway DDL**: Created `db/migration/V1__init_schema.sql` defining composite indexes, foreign keys, and audit constraints.
- **Ingestion Service**: Implemented [`NotificationIngestionService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationIngestionService.java) with pre-persistence idempotency lookup and concurrent collision safety (`DataIntegrityViolationException` recovery).
- **Query Service**: Implemented [`NotificationQueryService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationQueryService.java) calculating aggregated counts and formatting chronological audit events.

### 1.3 Validation Evidence
- **Automated Tests**:
  - `NotificationIngestionApiTest.testNewNotificationSubmission_Returns202Accepted`: Verifies valid submission persistence and initial `ACCEPTED` audit entry.
  - `NotificationIngestionApiTest.testDuplicateSubmissionWithSameIdempotencyKey_Returns200Ok`: Verifies duplicate replay returns HTTP 200 OK and logs `SUPPRESSED_DUPLICATE`.
  - `NotificationEndToEndIntegrationTest.testDuplicateSubmissionIdempotency`: Validates composite uniqueness and deduplication across database boundaries.
- **Live Terminal Proof (`.\demo.ps1`)**:
  ```
  [HTTP 202 Accepted] Notification accepted with ID: d51ff4c3-ce86-4b93-aff5-9de24554240a (Initial Status: ACCEPTED)
  [HTTP 200 OK] Existing Notification Returned: d51ff4c3-ce86-4b93-aff5-9de24554240a
  Updated Audit Timeline:
    [09/19/2026 02:29:04] ACCEPTED : Notification accepted from source: trading-platform
    [09/19/2026 02:29:06] SUPPRESSED_DUPLICATE : Duplicate submission suppressed for idempotency key: idem-trade-...
  ```

---

## Scenario 2: Brownfield Scenario (Delivery Execution & Failure Resilience)

### 2.1 Requirement Decomposition
Enhance the system with production-grade failure handling, resilience, and error classification:
- **Resilience4j Integration**: Wrap downstream provider dispatch in `@Retry(name = "notificationDeliveryRetry")` with bounded attempts and exponential backoff.
- **Error Classification**:
  - *Transient Failures* (`HTTP 429` Rate Limit, `HTTP 503` Service Unavailable, Timeouts): Must trigger bounded exponential backoff retries and record `RETRY_SCHEDULED` on each attempt.
  - *Permanent Failures* (`HTTP 400` Invalid Recipient, `HTTP 401` Authentication/Authorization Failure): Must terminate immediately on Attempt #1 with zero retries and transition the status to `FAILED`.
- **Delivery Progress Granularity**: Record provider response codes, execution times (ms), attempt counters, and error categories per attempt.

### 2.2 Execution & Implementation
- **Provider Abstraction**: Implemented [`EmailChannelProvider.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/channel/EmailChannelProvider.java) (AWS SES) and [`SmsChannelProvider.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/channel/SmsChannelProvider.java) (Twilio) with realistic downstream fault simulation.
- **Dispatch Service**: Implemented [`ProviderDispatchService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/delivery/ProviderDispatchService.java) utilizing Resilience4j:
  ```yaml
  resilience4j:
    retry:
      instances:
        notificationDeliveryRetry:
          max-attempts: 3
          wait-duration: 1000ms
          exponential-backoff-multiplier: 2
          retry-exceptions:
            - com.schwab.notification.exception.TransientProviderException
          ignore-exceptions:
            - com.schwab.notification.exception.PermanentProviderException
  ```
- **Fallback Recovery**: Attached `fallbackMethod = "onRetryExhausted"` to mark the delivery attempt `FAILED` and record terminal audit logs when retry limits are reached.

### 2.3 Validation Evidence
- **Automated Tests**:
  - `DeliveryWorkerResilienceTest.testTransientFailure_TriggersBoundedRetryAndRecordsRetryScheduled`: Verifies 3 attempts made with `RETRY_SCHEDULED` audit entries before final failure.
  - `DeliveryWorkerResilienceTest.testPermanentFailure_TerminatesImmediatelyWithoutRetry`: Verifies termination strictly on attempt #1 with zero retries.
- **Live Terminal Proof (`.\demo.ps1`)**:
  ```
  Scenario 4 (Transient):
    Aggregate Status: FAILED
    Attempts Made: 4 | Final Status: FAILED | Error Category: RATE_LIMIT_EXCEEDED
    [02:29:21] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #1): Rate limit exceeded...
    [02:29:21] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #2): Rate limit exceeded...
    [02:29:22] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #3): Rate limit exceeded...
    [02:29:22] FAILED          : Delivery failed after exhausting retries for recipient 'client_risk_03' on EMAIL

  Scenario 5 (Permanent):
    Aggregate Status: FAILED
    Attempt Number: 1 (Zero Retries) | Status: FAILED | Error Category: INVALID_RECIPIENT
    [02:29:25] FAILED : Permanent provider rejection (HTTP 400) for recipient 'client_bad_04' on EMAIL
  ```

---

## Scenario 3: Ambiguous Requirement Scenario (Conflict Resolution & Intelligent Fallback)

### 3.1 Requirement Decomposition & Architectural Ambiguity
In enterprise wealth management and brokerage operations, alert requirements often produce sharp conflicts:
1. **Source Demands vs User Opt-Outs**: Trading system requests `SMS`, but the user opted out of text messages (TCPA compliance).
2. **Quiet-Hour Windows**: Non-emergency alerts submitted at 2:00 AM local time targeting intrusive channels (`SMS`) when user quiet hours are active (22:00–07:00).
3. **Regulatory Duty-of-Care vs Privacy**: High-urgency margin calls or fraud warnings (`Severity.CRITICAL`) during quiet hours for opted-out users. Dropping or delaying creates multi-million dollar capital loss and regulatory non-compliance (FINRA Rule 4210 / SEC customer disclosure obligations).

### 3.2 Execution & Architectural Decision Record (ADR-001)
Drafted [`docs/scenarios/ambiguous-routing.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/scenarios/ambiguous-routing.md) adopting a **Hierarchical Intelligent Fallback Engine**:
- **Tier 1 (Regulatory Override)**: If `Severity == CRITICAL`, mandatory multi-channel dispatch (`SMS + EMAIL`) is enforced. User quiet hours and non-statutory opt-outs are overridden. Audit action: `REGULATORY_OVERRIDE_APPLIED`.
- **Tier 2 (Quiet Hours Diversion)**: For non-critical notifications, intrusive channels (`SMS`) active during user quiet hours are diverted to persistent, non-intrusive channels (`EMAIL`). Audit action: `QUIET_HOURS_FALLBACK`.
- **Tier 3 (Opt-Out Fallback)**: For non-critical notifications, if a requested channel is opted out, the system diverts to `EMAIL`. Audit action: `CHANNEL_FALLBACK_APPLIED`.

Implemented in [`RoutingService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/RoutingService.java).

### 3.3 Validation Evidence
- **Automated Tests**:
  - `IntelligentFallbackRoutingTest.testTier1_RegulatoryOverride_CriticalSeverityOverridesOptOutAndQuietHours`: Proves CRITICAL margin alert forces both SMS and EMAIL and logs `REGULATORY_OVERRIDE_APPLIED`.
  - `IntelligentFallbackRoutingTest.testTier2_QuietHoursDiversion_SmsDivertedToEmailDuringQuietHours`: Proves SMS sent during quiet hours window diverts to EMAIL and logs `QUIET_HOURS_FALLBACK`.
  - `IntelligentFallbackRoutingTest.testTier3_OptOutFallback_SmsDivertedToEmail`: Proves SMS opt-out diverts to EMAIL and logs `CHANNEL_FALLBACK_APPLIED`.
- **Live Terminal Proof (`.\demo.ps1`)**:
  ```
  Scenario 3 (Intelligent Fallback):
    Aggregate Status: DELIVERED
    [02:29:06] ACCEPTED                 : Notification accepted from source: portfolio-advisory
    [02:29:06] CHANNEL_FALLBACK_APPLIED : User opted out of requested channel 'SMS'. Applied intelligent fallback to EMAIL.
    [02:29:06] ROUTED                   : Routing completed for recipient 'client_wealth_02'. Final channels: [EMAIL]
    [02:29:06] DELIVERED                : Delivered successfully to recipient 'client_wealth_02' via EMAIL
  ```

