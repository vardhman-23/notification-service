# Charles Schwab Notification Management Service

Enterprise Notification Management Service prototype developed for the **Charles Schwab AI-Assisted Software Engineering Assessment**.

This production-oriented prototype accepts notification and alert requests from diverse business and technical systems (Trading Platform, Risk Engine, Account Services, Wealth Management) and reliably delivers them across configurable channels (Email, SMS, Webhook) with **composite idempotency**, **strategy-based channel routing with intelligent fallback**, **bounded Resilience4j retries**, and an **immutable, PII-sanitized audit trail**.


---

## Assessment Deliverables & Reviewer Documentation

| Deliverable | Key Documentation | Implementation & Proof |
|---|---|---|
| **1. Working Prototype** | [`SUBMISSION.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/SUBMISSION.md) | Runnable Spring Boot 3.3.4 service on `http://localhost:8080`, live demo via [`demo.ps1`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.ps1) & [`demo.sh`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.sh) |
| **2. Architecture Overview** | [`docs/ARCHITECTURE.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/ARCHITECTURE.md) | Component architecture, control flow sequence diagrams, state machine, DDD domain entities |
| **3. Three Scenarios** | [`docs/SCENARIOS.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/SCENARIOS.md)<br>[`docs/scenarios/ambiguous-routing.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/scenarios/ambiguous-routing.md) | Greenfield, Brownfield (Resilience4j), and Ambiguous Requirement (ADR-001 Intelligent Fallback) with decomposition, execution, & empirical validation |
| **4. Setup Instructions** | [`README.md` (Section 1)](#1-quick-start-run-locally-in-seconds) | Zero-dependency local startup (`dev` profile with H2), Docker Compose orchestration |
| **5. Testing & Trade-offs** | [`docs/TESTING_AND_TRADE_OFFS.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/TESTING_AND_TRADE_OFFS.md) | Testing pyramid (32 automated tests passing), traceability matrix, system limitations, and architectural trade-offs |

---

## 1. Quick Start: Run Locally in Seconds

The project is pre-configured with a **zero-dependency `dev` profile** using an in-memory PostgreSQL-compatible H2 database with H2 Web Console. No external Docker, PostgreSQL, or Redis installation is required.

### Prerequisites
- Java 21 or higher (verified on JDK 21 & JDK 24)
- PowerShell (Windows) or Bash (macOS/Linux)

### 1. Launch the Application
```powershell
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```
*The service will start on `http://localhost:8080`.*

### 2. Run the Interactive End-to-End Demo
Open a second terminal in the project directory and run the automated verification script:
```powershell
# Windows (PowerShell)
.\demo.ps1

# macOS / Linux (Bash)
chmod +x demo.sh
./demo.sh
```
*This executes and colorfully displays all 5 assessment scenarios live against the running application.*

### 3. Run the Automated Test Suite
```powershell
# Runs all 32 unit, integration, and resilience tests
.\mvnw.cmd clean test
```

---

## 2. System Architecture & Component Design

```mermaid
flowchart TD
    Client[Upstream Clients: Trading, Risk, Advisory] -->|POST /api/v1/notifications| IngestionCtrl[NotificationController]
    
    subgraph Ingestion ["Stage 1 & 2: Ingestion & Deduplication Gate"]
        IngestionCtrl --> IngestSvc[NotificationIngestionService]
        IngestSvc -->|Composite Key Check| IdemRepo[(Idempotency / DB)]
        IngestSvc -->|Log ACCEPTED / SUPPRESSED| AuditRepo[(AuditLog)]
        IngestSvc -->|Publish| AcceptedEvent[NotificationAcceptedEvent]
    end

    subgraph AsyncPipeline ["Stage 2 & 3: Asynchronous Pipeline"]
        AcceptedEvent -->|@Async @TransactionalEventListener| Pipeline[AsyncNotificationPipeline]
        Pipeline --> RouteSvc[RoutingService]
        
        subgraph RoutingEngine ["Stage 4: Strategy Routing & Intelligent Fallback"]
            RouteSvc --> StratReg[ChannelProviderRegistry]
            StratReg --> EmailProv[EmailChannelProvider - AWS SES]
            StratReg --> SmsProv[SmsChannelProvider - Twilio]
            RouteSvc -->|ADR-001 Policy| FallbackEngine{Severity == CRITICAL?}
            FallbackEngine -- Yes --> RegOverride[Tier 1: Regulatory Override - Force SMS+EMAIL]
            FallbackEngine -- No --> QuietCheck{Quiet Hours / Opt-Out?}
            QuietCheck -- Yes --> FallbackAction[Tier 2/3: Divert SMS -> EMAIL]
            QuietCheck -- No --> StandardRoute[Standard Preferred Channel]
        end

        RouteSvc --> StagedAttempts[(Delivery Attempts Staged)]
        Pipeline --> DelivWorker[DeliveryWorker]
        
        subgraph Resilience ["Stage 3: Resilient Delivery & Classification"]
            DelivWorker --> DispatchSvc[ProviderDispatchService]
            DispatchSvc -->|Resilience4j @Retry| Provider[Provider Dispatch]
            Provider -->|HTTP 429/503/Timeout| TransientErr[Transient: Exponential Backoff Retry]
            Provider -->|HTTP 400/401| PermErr[Permanent: Immediate Termination]
        end
    end

    subgraph QueryAPI ["Stage 3: Query & Observability"]
        Client -->|GET /api/v1/notifications/{id}| QueryCtrl[NotificationController]
        QueryCtrl --> QuerySvc[NotificationQueryService]
        QuerySvc --> DBView[(Notification + Attempts + Audit)]
    end
```

---

## 3. Charles Schwab Assessment Mapping

| Scenario / Deliverable | Implementation Details | Key Files |
|---|---|---|
| **3.1 Greenfield Scenario** | Complete submission API, recipient channel preferences, asynchronous event pipeline, staged delivery attempts, multi-state lifecycle (`ACCEPTED`, `ROUTED`, `DELIVERING`, `DELIVERED`, `FAILED`), aggregate status retrieval. | [`NotificationController.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/api/controller/NotificationController.java)<br>[`NotificationIngestionService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationIngestionService.java)<br>[`NotificationQueryService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationQueryService.java) |
| **3.2 Brownfield Scenario** | Fault tolerance & error classification: Resilience4j `@Retry` with exponential backoff for transient failures (`HTTP 429`, `503`, timeouts), immediate termination for permanent rejections (`HTTP 400`, `401`), failure logging with `RETRY_SCHEDULED`. | [`ProviderDispatchService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/delivery/ProviderDispatchService.java)<br>[`DeliveryWorker.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/delivery/DeliveryWorker.java)<br>[`DeliveryWorkerResilienceTest.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/DeliveryWorkerResilienceTest.java) |
| **3.3 Ambiguous Scenario** | Architectural Decision Record (**ADR-001**) defining a 3-tier deterministic Intelligent Fallback Engine resolving source demands vs recipient opt-outs vs quiet hours vs FINRA/SEC duty-of-care regulatory overrides. | [`ambiguous-routing.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/scenarios/ambiguous-routing.md)<br>[`RoutingService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/RoutingService.java)<br>[`IntelligentFallbackRoutingTest.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/IntelligentFallbackRoutingTest.java) |
| **4.4 Deduplication & Idempotency** | Composite unique constraint (`source_system`, `event_id`, `idempotency_key`). Resubmissions return HTTP 200 with existing record without creating duplicate deliveries, recording `SUPPRESSED_DUPLICATE` in audit history. | [`NotificationIngestionService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationIngestionService.java)<br>[`V1__init_schema.sql`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/resources/db/migration/V1__init_schema.sql) |
| **4.9 Audit History** | Tamper-evident, chronological audit logging across all state transitions (`ACCEPTED`, `ROUTED`, `REGULATORY_OVERRIDE_APPLIED`, `CHANNEL_FALLBACK_APPLIED`, `DELIVERED`, `RETRY_SCHEDULED`, `FAILED`). PII-sanitized payloads. | [`AuditLog.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/domain/model/AuditLog.java)<br>[`NotificationQueryService.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/schwab/notification/service/NotificationQueryService.java) |

---

## 4. REST API Guide & Demonstrable Scenarios

### Endpoint Summary
| Method | Path | Description | Response Code |
|---|---|---|---|
| `POST` | `/api/v1/notifications` | Submit alert notification | `202 Accepted` (new) / `200 OK` (duplicate) |
| `GET` | `/api/v1/notifications/{id}` | Query aggregate status, attempts, & audit trail | `200 OK` / `404 Not Found` |
| `POST` | `/api/v1/notifications/{id}/dispatch` | Manually trigger routing & delivery execution | `200 OK` |
| `GET` | `/actuator/health` | Service health status | `200 OK` |
| `GET` | `/h2-console` | H2 Web Console (`jdbc:h2:mem:notification_dev_db`) | `200 OK` |

---

### Scenario 1: CRITICAL Notification & Regulatory Override
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "trading-platform",
    "eventId": "trade-evt-100",
    "idempotencyKey": "idem-margin-100",
    "notificationType": "MARGIN_CALL",
    "severity": "CRITICAL",
    "priority": "URGENT",
    "subject": "Margin Breach Alert",
    "body": "Your margin deposit is required immediately.",
    "recipients": [
      {
        "recipientId": "user_42",
        "destination": "trader@schwab.com",
        "preferredChannels": "EMAIL"
      }
    ]
  }'
```
*Expected*: Returns HTTP 202 Accepted. Tier 1 Regulatory Override forces **both EMAIL and SMS** attempts despite user only selecting EMAIL. Status transitions to `DELIVERED`.

---

### Scenario 2: Deduplication Gate
Resend the exact same JSON payload as above.
*Expected*: Returns **HTTP 200 OK** with identical `notificationId`. Audit trail appends `SUPPRESSED_DUPLICATE` without creating new delivery attempts.

---

### Scenario 3: Intelligent Fallback Routing (Opt-Out & Quiet Hours)
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "portfolio-service",
    "eventId": "port-evt-200",
    "idempotencyKey": "idem-port-200",
    "notificationType": "REBALANCE_NOTICE",
    "severity": "LOW",
    "priority": "NORMAL",
    "subject": "Portfolio Rebalanced",
    "body": "Your portfolio was rebalanced to 70/30.",
    "recipients": [
      {
        "recipientId": "user_99",
        "destination": "investor@schwab.com",
        "preferredChannels": "SMS",
        "optedOutChannels": "SMS"
      }
    ]
  }'
```
*Expected*: Recipient requested SMS but opted out. ADR-001 Tier 3 policy diverts to `EMAIL`. Audit log records `CHANNEL_FALLBACK_APPLIED`.

---

### Scenario 4: Transient Failure & Bounded Retries (Resilience4j)
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "risk-engine",
    "eventId": "risk-evt-300",
    "idempotencyKey": "idem-rate-300",
    "notificationType": "VOLATILITY_ALERT",
    "severity": "HIGH",
    "priority": "HIGH",
    "subject": "Market Alert",
    "body": "VIX exceeded threshold.",
    "recipients": [
      {
        "recipientId": "client_risk",
        "destination": "transient-429@test.com",
        "preferredChannels": "EMAIL"
      }
    ]
  }'
```
*Expected*: Downstream AWS SES simulates HTTP 429 Rate Limit. Resilience4j executes 3 retries with exponential backoff. Audit log records `RETRY_SCHEDULED` across attempts and final `FAILED` when retries are exhausted.

---

### Scenario 5: Permanent Failure & Immediate Halt
```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "account-service",
    "eventId": "acct-evt-400",
    "idempotencyKey": "idem-perm-400",
    "notificationType": "SECURITY_NOTICE",
    "severity": "LOW",
    "priority": "LOW",
    "subject": "Password Changed",
    "body": "Security notice.",
    "recipients": [
      {
        "recipientId": "client_bad",
        "destination": "permanent-400-bad-syntax",
        "preferredChannels": "EMAIL"
      }
    ]
  }'
```
*Expected*: Destination syntax rejection (HTTP 400). Terminates immediately on attempt #1 with zero retries. Audit log records `FAILED: Permanent provider rejection`.

---

## 5. Query Notification Status & Audit Timeline

```bash
curl http://localhost:8080/api/v1/notifications/{notificationId}
```

### Example Response:
```json
{
  "notificationId": "8a424827-6d86-429f-8317-6b38a47c6f81",
  "sourceSystem": "trading-platform",
  "eventId": "trade-evt-100",
  "idempotencyKey": "idem-margin-100",
  "notificationType": "MARGIN_CALL",
  "severity": "CRITICAL",
  "priority": "URGENT",
  "aggregateStatus": "DELIVERED",
  "deliverySummary": {
    "totalChannels": 2,
    "totalAttempts": 2,
    "successfulCount": 2,
    "failedCount": 0,
    "pendingCount": 0,
    "retryingCount": 0
  },
  "deliveryProgress": [
    {
      "channel": "EMAIL",
      "provider": "AWS_SES",
      "status": "SENT",
      "attemptNumber": 1,
      "providerResponseCode": "250_OK",
      "sentAt": "2026-09-18T22:18:17.226289Z"
    },
    {
      "channel": "SMS",
      "provider": "TWILIO",
      "status": "SENT",
      "attemptNumber": 1,
      "providerResponseCode": "200_DELIVERED",
      "sentAt": "2026-09-18T22:18:17.227878Z"
    }
  ],
  "auditTimeline": [
    {
      "action": "ACCEPTED",
      "metadataReason": "Notification accepted from source: trading-platform",
      "timestamp": "2026-09-18T22:18:17.080143Z"
    },
    {
      "action": "ROUTED",
      "metadataReason": "Severity CRITICAL forced channels [EMAIL, SMS] for recipient 'client_trader_01'",
      "timestamp": "2026-09-18T22:18:17.171085Z"
    },
    {
      "action": "DELIVERED",
      "metadataReason": "Delivered successfully to recipient 'client_trader_01' via EMAIL",
      "timestamp": "2026-09-18T22:18:17.226289Z"
    },
    {
      "action": "DELIVERED",
      "metadataReason": "Delivered successfully to recipient 'client_trader_01' via SMS",
      "timestamp": "2026-09-18T22:18:17.227878Z"
    }
  ]
}
```

---

## 6. Testing & Quality Assurance

The test suite contains **32 tests** covering every architectural layer:

- **Schema Migration Tests**: `FlywaySchemaMigrationTest` validates flyway DDL scripts against PostgreSQL mode.
- **Idempotency & Ingestion Tests**: `NotificationIngestionApiTest` verifies payload validation, 202 Accepted, duplicate suppression, and composite unique keys.
- **Strategy & Routing Tests**: `RoutingServiceTest` and `IntelligentFallbackRoutingTest` verify channel resolution, quiet-hour diversion windows, opt-out fallbacks, and regulatory overrides.
- **Resilience & Fault Handling Tests**: `DeliveryWorkerResilienceTest` tests Resilience4j retry intervals, transient error backoff, and immediate permanent error halting.
- **End-to-End Tests**: `NotificationEndToEndIntegrationTest` tests all 5 scenarios end-to-end against full web and persistence context.
- **Testcontainers**: `NotificationEndToEndTestcontainersTest` contains real PostgreSQL 16 container tests with graceful degradation (`@DisabledIf("isDockerUnavailable")`) when running in environments without Docker daemon.

Run the test suite with:
```powershell
.\mvnw.cmd test
```

---

## 7. Configuration & Profiles

| Profile | Datasource | Flyway | Auto-Dispatch | Intended Environment |
|---|---|---|---|---|
| **`dev`** (default) | H2 In-Memory (`MODE=PostgreSQL`) | Disabled | Enabled (`true`) | Local prototype, zero prerequisites |
| **`test`** | H2 In-Memory (`MODE=PostgreSQL`) | Disabled | Disabled (`false`) | Deterministic JUnit test execution |
| **`prod` / `postgres`** | PostgreSQL 16 (`notification_db:5432`) | Enabled | Enabled (`true`) | Containerized / Production deployment |

To run against real PostgreSQL using Docker:
```powershell
docker compose up -d
SPRING_PROFILES_ACTIVE=postgres .\mvnw.cmd spring-boot:run
```
