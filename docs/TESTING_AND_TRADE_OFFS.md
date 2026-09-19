# Testing Approach, Limitations & Engineering Trade-offs

Documentation detailing the testing strategy, known system limitations, and defensible architectural trade-offs for the **Enterprise Notification Management Service**.

---

## 1. Testing Approach & Strategy

The application employs an enterprise-grade layered testing pyramid designed to validate deterministic logic in isolation while verifying full end-to-end integration across database, resilience, security, and web layers.

```
       /\
      /  \     Testcontainers (Real PostgreSQL 16 Dialect)
     /----\    NotificationEndToEndTestcontainersTest
    /      \   End-to-End MockMvc Integration Tests
   /--------\  NotificationEndToEndIntegrationTest, ConcurrencyTests
  /          \ Resilience, Rate Limit & Fault Tolerance Tests (Resilience4j & Bucket4j)
 /------------\DeliveryWorkerResilienceTest, IntelligentFallbackRoutingTest, RateLimitFilterTest
/              \Unit, Slice & Repository Tests (DTO Validation, Ingestion, Flyway DDL)
----------------NotificationIngestionApiTest, RoutingServiceTest, RepositoryTests, DtoValidationTest
```

### 1.1 Test Suite Overview & Verification Metrics

- **Total Tests**: **107 passing tests**, 0 failures, 0 errors, 4 skipped (Testcontainers environment-specific).
- **Line Coverage**: **98.88%** (968 / 979 executable bytecode lines) — *far exceeding the strict 97.0% JaCoCo build gate*.
- **Branch Coverage**: **86.33%** (221 / 256 logical branches).
- **Comprehensive Report**: See [`docs/TEST_REPORT.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/TEST_REPORT.md) for full package-by-package metrics and JaCoCo verification output.

### 1.2 Layer-by-Layer Test Breakdown

| Test Suite / Class | Focus Area | Key Invariants Verified |
|---|---|---|
| [`NotificationIngestionApiTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/NotificationIngestionApiTest.java) | Ingestion API & DTO validation | HTTP 202 Accepted on valid submission; HTTP 200 OK on duplicate submission; bean validation constraints; composite idempotency key persistence. |
| [`NotificationIngestionServiceTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/service/NotificationIngestionServiceTest.java) | Service Ingestion & Race Handling | Deduplication cache check; `DataIntegrityViolationException` recovery on simultaneous race condition; audit logging. |
| [`RoutingServiceTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/RoutingServiceTest.java) | Strategy Channel Routing | Channel resolution based on preferences; provider registry matching; stage creation for delivery attempts; routing audit logs. |
| [`IntelligentFallbackRoutingTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/IntelligentFallbackRoutingTest.java) | ADR-001 Policy Engine | Tier 1 Regulatory Override (CRITICAL forces SMS+EMAIL); Tier 2 Quiet-Hours Diversion (SMS $\rightarrow$ EMAIL); Tier 3 Opt-Out Fallback (SMS $\rightarrow$ EMAIL). |
| [`DeliveryWorkerResilienceTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/DeliveryWorkerResilienceTest.java) | Resilience4j Retry, DLQ & Faults | HTTP 429 rate limit triggers bounded retries; HTTP 400 invalid recipient halts immediately; `RETRY_SCHEDULED` and `ROUTED_TO_DEAD_LETTER` audit trail generation; terminal `DEAD_LETTER` state. |
| [`RateLimitFilterTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/RateLimitFilterTest.java) | Security & DDoS Ingestion Defense | Bucket4j token bucket enforcing 100 req/min per client IP; HTTP 429 Too Many Requests response with `X-Rate-Limit-Remaining` headers; RFC 7807 compliance. |
| [`GlobalExceptionHandlerTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/GlobalExceptionHandlerTest.java) | RFC 7807 Problem Details | Standardized error taxonomy; correlation ID propagation in error payload; validation error breakdown. |
| [`NotificationQueryApiTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/NotificationQueryApiTest.java) | Query & Observability | Chronological audit timeline ordering; delivery progress calculation; summary counters (attempts, successful, failed, dead letter). |
| [`FlywaySchemaMigrationTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/FlywaySchemaMigrationTest.java) | Database DDL & Indexes | Flyway migration script integrity; PostgreSQL DDL syntax validation; composite unique constraint verification. |
| [`RepositoryTests`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/repository/) | JPA Persistence Slices | Query methods, custom ordering, and foreign key relations for `NotificationRepository`, `AuditLogRepository`, `DeliveryAttemptRepository`, and `IdempotencyRecordRepository`. |
| [`NotificationEndToEndIntegrationTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/NotificationEndToEndIntegrationTest.java) | End-to-End System Scenarios | Full lifecycle execution (Ingestion $\rightarrow$ Routing $\rightarrow$ Delivery $\rightarrow$ Status Query); on-demand dispatch API. |
| [`NotificationEndToEndTestcontainersTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/NotificationEndToEndTestcontainersTest.java) | Real PostgreSQL 16 Container | Validates exact PostgreSQL dialect and constraints with graceful skipping (`@DisabledIf("isDockerUnavailable")`) when Docker daemon is not active. |

---

## 2. Limitations

While the prototype represents a production-oriented architecture, several boundaries were deliberately established to maintain prototype runnable agility within the 2–3 day assessment window:

1. **Downstream Provider Integration**:
   - *Limitation*: Actual AWS SES SMTP/REST and Twilio SMPP APIs are simulated via `EmailChannelProvider` and `SmsChannelProvider` rather than requiring live paid cloud credentials.
   - *Production Path*: Replace mock send logic with official AWS Java SDK v2 (`software.amazon.awssdk.services.sesv2`) and Twilio SDK (`com.twilio.sdk.creator`).
2. **Distributed Locking & Cluster Worker Coordination**:
   - *Limitation*: Ingestion deduplication and worker executions rely on database unique constraints and Spring `@Async` thread pools on a single node.
   - *Production Path*: For a multi-node horizontal deployment, introduce Redis distributed locks (Redisson) or Kafka partitioned topics to prevent concurrent duplicate deliveries across instances.
3. **Idempotency Record Retention Eviction**:
   - *Limitation*: Idempotency records track expiration timestamps (`expires_at`), but an active background sweep task (e.g. `@Scheduled` job deleting records older than TTL) is not running continuously in the prototype.
   - *Production Path*: Implement a scheduled daily vacuum job or leverage Redis TTL key expiration.
4. **Timezone Granularity for Quiet Hours**:
   - *Limitation*: The prototype evaluates quiet hours using the recipient's local hour parameter (0–23) or current system hour.
   - *Production Path*: Store explicit IANA Timezone IDs (e.g. `America/New_York`) per recipient and dynamically evaluate `ZonedDateTime` at delivery dispatch time.

---

## 3. Engineering Trade-offs

| Decision / Trade-off | Option Chosen | Alternative Rejected | Rationale & Justification |
|---|---|---|---|
| **Asynchronous Handoff vs Immediate Synchronous Delivery** | **Asynchronous Event-Driven Pipeline** (`HTTP 202 Accepted` + `@Async` listener) | Synchronous blocking delivery during `POST /api/v1/notifications` | High-frequency trading and order processing systems cannot tolerate 1–3 second blocking latencies from downstream email/SMS network calls. Decoupling ingestion guarantees sub-50ms API response times. |
| **Database Transaction Scoping & Connection Preservation** | **Strictly Scoped `REQUIRES_NEW` Persistence Blocks** | Broad `@Transactional` wrapping entire delivery workflows | External provider HTTP calls can take 500ms–5000ms. Holding database connections during network I/O leads to connection pool exhaustion under modest load. Isolating database updates to short `REQUIRES_NEW` transactions ensures DB connections are held for < 5ms. |
| **Concurrency & Race Conditions** | **Database Unique Constraints + Graceful Catch** | Check-Then-Act Pattern (SELECT then INSERT) | In high-concurrency environments, two identical requests can simultaneously clear the SELECT check. Enforcing `uq_idempotency_source_recipient_key` and catching `DataIntegrityViolationException` guarantees zero duplicates even under extreme parallel submission. |
| **Dead-Letter Routing vs Dropping Failed Messages** | **Dead-Letter Routing (`DEAD_LETTER` state & DLQ audit log)** | Silent dropping or infinite retries | Bounded retries prevent provider quota drain. Routing permanently failed messages to `DEAD_LETTER` terminal state preserves full regulatory auditability and triggers alerts for platform SRE intervention. |
| **Delivery Guarantee: At-Least-Once vs Exactly-Once** | **At-Least-Once Delivery with Upstream Idempotency** | Distributed 2-Phase Commit (2PC) or strict Exactly-Once side-effects | True exactly-once side-effects across third-party communication networks (telecom carriers, email gateways) are technically impossible. The system relies on composite idempotency gates and provider deduplication IDs to prevent duplicate user exposure. |
| **Routing Conflict Policy: Deterministic Hierarchy vs Dropping Alerts** | **Hierarchical Fallback Engine (ADR-001)** | Source-Always-Wins OR Strict-Drop | Source-Always-Wins violates TCPA regulations (legal liability). Strict-Drop exposes the brokerage to regulatory sanctions (FINRA Rule 4210) for undelivered margin calls. The 3-tier deterministic fallback satisfies both legal compliance and client duty-of-care. |
| **Local Prototype Persistence: Zero-Dependency H2 vs Hard Docker Dependency** | **Default `dev` Profile with PostgreSQL-compatible H2** | Mandatory Docker Compose / PostgreSQL setup | Evaluators and developers frequently test assignments in restricted environments where Docker daemon may not be installed or permitted. Making H2 in PostgreSQL mode the default guarantees runnable prototype success in 10 seconds. |
| **Rate Limiting & Ingestion Protection** | **Bucket4j Token Bucket Per IP (100 req/min)** | Unprotected endpoints or heavy external API gateways | Protects database and worker pools against burst ingestion traffic or misbehaving client loops directly at the servlet filter layer without introducing external gateway dependencies. |
| **Audit Trail Storage: PII Sanitization vs Raw Payload Storage** | **PII-Sanitized Payload Summary** | Persisting raw body and client credentials | Banking regulations (GLBA, GDPR, PCI-DSS) strictly prohibit storing cleartext credentials, card numbers, or sensitive financial payloads in operational audit tables accessible to platform engineers. |

