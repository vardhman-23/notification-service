# Notification Management Service

Enterprise Notification Management Service prototype built for the **Charles Schwab AI-Assisted Software Engineering Assessment**.

This service accepts alert and notification requests from diverse upstream systems (e.g., Trading Platform, Risk Engine, Account Services) and reliably delivers them across configurable channels (Email, SMS, Slack, Webhook, In-App) with full idempotency, routing logic, bounded retries, and an immutable audit trail.

---

## 1. Technology Stack

- **Runtime**: Java 21 (`--release 21`) / Spring Boot 3.3.4
- **Web & Core**: `spring-boot-starter-web`, `spring-boot-starter-validation`
- **Data Persistence**: `spring-boot-starter-data-jpa`, PostgreSQL 16 Driver, Hibernate 6
- **Database Migrations**: Flyway (`flyway-core`, `flyway-database-postgresql`)
- **Caching & Idempotency Store**: Redis 7 via `spring-boot-starter-data-redis` (Lettuce)
- **Fault Tolerance**: Resilience4j (`resilience4j-spring-boot3`) for bounded exponential retry & circuit breaking
- **Observability**: Spring Boot Actuator (`/actuator/health`, `/actuator/info`, `/actuator/metrics`)
- **Containers**: Docker Compose for PostgreSQL 16 and Redis 7
- **Testing**: JUnit 5, AssertJ, H2 in-memory test profile

---

## 2. Architecture & Domain Model

```
com.schwab.notification
├── NotificationServiceApplication.java
└── domain
    ├── model
    │   ├── Notification.java            # Main aggregate root (submission, status, payload)
    │   ├── NotificationRecipient.java   # Recipient address & channel preferences
    │   ├── DeliveryAttempt.java         # Per-recipient, per-channel delivery attempts & errors
    │   ├── AuditLog.java                # Immutable audit log (PII-free)
    │   └── IdempotencyRecord.java       # Submission deduplication record
    ├── repository
    │   ├── NotificationRepository.java
    │   ├── DeliveryAttemptRepository.java
    │   ├── AuditLogRepository.java
    │   └── IdempotencyRecordRepository.java
    └── types
        ├── NotificationStatus.java      # SUBMITTED, PROCESSING, DELIVERED, PARTIALLY_DELIVERED, FAILED, EXPIRED, SUPPRESSED_DUPLICATE
        ├── Severity.java                # LOW, MEDIUM, HIGH, CRITICAL
        ├── Priority.java                # LOW, NORMAL, HIGH, URGENT
        ├── ChannelType.java             # EMAIL, SMS, SLACK, IN_APP, WEBHOOK
        ├── DeliveryStatus.java          # PENDING, SENT, RETRYING, FAILED, CANCELLED
        ├── ErrorCategory.java           # TRANSIENT_PROVIDER_FAILURE, RATE_LIMIT_EXCEEDED, TIMEOUT, PERMANENT_PROVIDER_REJECTION, INVALID_RECIPIENT, AUTH_ERROR
        └── AuditEventType.java          # NOTIFICATION_ACCEPTED, NOTIFICATION_REJECTED, ROUTING_COMPLETED, DELIVERY_QUEUED, etc.
```

---

## 3. Database Schema (`V1__init_schema.sql`)

- **`notifications`**: Contains message payload, severity, priority, status, timestamps, scheduling, and expiration.
- **`notification_recipients`**: Target recipients, addresses, and user-specified channel preferences.
- **`delivery_attempts`**: Individual attempts per channel, provider, retry count, failure categorization (`ErrorCategory`), and retry scheduling.
- **`audit_logs`**: Tamper-evident, structured audit log tracking all state transitions without storing sensitive credentials or unmasked message payloads.
- **`idempotency_records`**: Hash and deduplication tracking with bounded retention expiry.

---

## 4. Running the Application

### Prerequisites
- JDK 21 or higher installed.
- (Optional for containers) Docker and Docker Compose.

### Start Infrastructure via Docker Compose
```bash
docker compose up -d
```
This launches:
- **PostgreSQL 16** on `localhost:5432` (db: `notification_db`, user: `postgres`, pass: `postgres`)
- **Redis 7** on `localhost:6379`

### Build & Run Tests
A pre-configured Maven Wrapper is included in the project:
```powershell
# Run the test suite (H2 in-memory profile)
.\mvnw.cmd test

# Run the Spring Boot application (PostgreSQL + Redis profile)
.\mvnw.cmd spring-boot:run
```

---

## 5. Charles Schwab Assessment Mapping

| Requirement Section | Component | Description |
|---------------------|-----------|-------------|
| **4.1 Submit a Notification** | `Notification`, `NotificationRecipient` | Supports correlation ID, source system, severity, priority, scheduling, and expiration. |
| **4.2 Notification Status** | `NotificationStatus`, `DeliveryStatus` | Defensible multi-state lifecycle with per-recipient and per-channel granularity. |
| **4.3 Channel Routing** | `ChannelType`, `Severity`, preferences | Foundation for routing rules and severity-based escalation. |
| **4.4 Deduplication & Idempotency** | `IdempotencyRecord`, Redis integration | Submission hash tracking and state reflection. |
| **4.5 Retry & Failure Handling** | `ErrorCategory`, `Resilience4j` | Distinguishes transient vs permanent vs rate-limit vs auth failures with bounded retries. |
| **4.9 Audit History** | `AuditLog`, `AuditEventType` | Audit trail recording all transitions with sensitive data sanitization. |

