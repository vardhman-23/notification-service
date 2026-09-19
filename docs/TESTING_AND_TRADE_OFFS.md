# Testing Approach, Limitations & Engineering Trade-offs

Documentation detailing the testing strategy, known system limitations, and defensible architectural trade-offs for the **Charles Schwab Notification Management Service**.

---

## 1. Testing Approach & Strategy

The application employs a layered testing pyramid designed to validate deterministic logic in isolation while verifying full end-to-end integration across database, resilience, and web layers.

```
       /\
      /  \     Testcontainers (Real PostgreSQL 16)
     /----\    NotificationEndToEndTestcontainersTest
    /      \   End-to-End MockMvc Integration Tests
   /--------\  NotificationEndToEndIntegrationTest
  /          \ Resilience & Fault Tolerance Tests (Resilience4j)
 /------------\DeliveryWorkerResilienceTest, IntelligentFallbackRoutingTest
/              \Unit & Slice Tests (DTO Validation, Ingestion, Flyway DDL)
----------------NotificationIngestionApiTest, RoutingServiceTest, FlywaySchemaMigrationTest
```

### 1.1 Layer-by-Layer Test Breakdown

| Test Class | Focus Area | Key Invariants Verified |
|---|---|---|
| [`NotificationIngestionApiTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/NotificationIngestionApiTest.java) | Ingestion API & DTO validation | HTTP 202 Accepted on valid submission; HTTP 200 OK on duplicate submission; bean validation constraints; composite idempotency key persistence. |
| [`RoutingServiceTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/RoutingServiceTest.java) | Strategy Channel Routing | Channel resolution based on preferences; provider registry matching; stage creation for delivery attempts; routing audit logs. |
| [`IntelligentFallbackRoutingTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/IntelligentFallbackRoutingTest.java) | ADR-001 Policy Engine | Tier 1 Regulatory Override (CRITICAL forces SMS+EMAIL); Tier 2 Quiet-Hours Diversion (SMS $\rightarrow$ EMAIL); Tier 3 Opt-Out Fallback (SMS $\rightarrow$ EMAIL). |
| [`DeliveryWorkerResilienceTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/DeliveryWorkerResilienceTest.java) | Resilience4j Retry & Faults | HTTP 429 rate limit triggers bounded retries; HTTP 400 invalid recipient halts immediately; `RETRY_SCHEDULED` and `FAILED` audit trail generation. |
| [`NotificationQueryApiTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/NotificationQueryApiTest.java) | Query & Observability | Chronological audit timeline ordering; delivery progress calculation; summary counters (attempts, successful, failed). |
| [`FlywaySchemaMigrationTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/FlywaySchemaMigrationTest.java) | Database DDL & Indexes | Flyway migration script integrity; PostgreSQL DDL syntax validation; composite unique constraint verification. |
| [`NotificationEndToEndIntegrationTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/NotificationEndToEndIntegrationTest.java) | End-to-End System Scenarios | Full lifecycle execution (Ingestion $\rightarrow$ Routing $\rightarrow$ Delivery $\rightarrow$ Status Query); on-demand dispatch API. |
| [`NotificationEndToEndTestcontainersTest`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/schwab/notification/NotificationEndToEndTestcontainersTest.java) | Real PostgreSQL 16 Container | Validates exact PostgreSQL dialect and constraints with graceful skipping (`@DisabledIf("isDockerUnavailable")`) when Docker daemon is not active. |

---

## 2. Limitations

While the prototype represents a production-oriented architecture, several boundaries were deliberately established to maintain prototype runnable agility within the 2–3 day assessment window:

1. **Downstream Provider Integration**:
   - *Limitation*: Actual AWS SES SMTP/REST and Twilio SMPP APIs are simulated via `EmailChannelProvider` and `SmsChannelProvider` rather than requiring live paid cloud credentials.
   - *Production Path*: Replace mock send logic with official AWS Java SDK v2 (`software.amazon.awssdk.services.sesv2`) and Twilio SDK (`com.twilio.sdk.creator`).
2. **Distributed Locking & Worker Coordination**:
   - *Limitation*: Ingestion deduplication and worker executions rely on database transaction isolation and Spring `@Async` threads on a single node.
   - *Production Path*: For a multi-node horizontal deployment, introduce Redis distributed locks (Redisson) or Kafka partitioned topics to prevent concurrent duplicate deliveries across instances.
3. **Idempotency Record Retention Eviction**:
   - *Limitation*: Idempotency records track expiration timestamps (`expires_at`), but an active background sweep task (e.g. `@Scheduled` job deleting records older than TTL) is not running continuously in the prototype.
   - *Production Path*: Implement a scheduled daily vacuum job or leverage Redis TTL expiration.
4. **Timezone Granularity for Quiet Hours**:
   - *Limitation*: The prototype evaluates quiet hours using the recipient's local hour parameter (0–23) or current system hour.
   - *Production Path*: Store explicit IANA Timezone IDs (e.g. `America/New_York`) per recipient and dynamically evaluate `ZonedDateTime` at delivery dispatch time.

---

## 3. Engineering Trade-offs

| Decision / Trade-off | Option Chosen | Alternative Rejected | Rationale & Justification |
|---|---|---|---|
| **Asynchronous Handoff vs Immediate Synchronous Delivery** | **Asynchronous Event-Driven Pipeline** (`HTTP 202 Accepted` + `@Async` listener) | Synchronous blocking delivery during `POST /api/v1/notifications` | High-frequency trading and order processing systems cannot tolerate 1–3 second blocking latencies from downstream email/SMS network calls. Decoupling ingestion guarantees sub-50ms API response times. |
| **Delivery Guarantee: At-Least-Once vs Exactly-Once** | **At-Least-Once Delivery with Upstream Idempotency** | Distributed 2-Phase Commit (2PC) or strict Exactly-Once side-effects | True exactly-once side-effects across third-party communication networks (telecom carriers, email gateways) are technically impossible. The system relies on composite idempotency gates and provider deduplication IDs to prevent duplicate user exposure. |
| **Routing Conflict Policy: Deterministic Hierarchy vs Dropping Alerts** | **Hierarchical Fallback Engine (ADR-001)** | Source-Always-Wins OR Strict-Drop | Source-Always-Wins violates TCPA regulations (legal liability). Strict-Drop exposes the brokerage to regulatory sanctions (FINRA Rule 4210) for undelivered margin calls. The 3-tier deterministic fallback satisfies both legal compliance and client duty-of-care. |
| **Local Prototype Persistence: Zero-Dependency H2 vs Hard Docker Dependency** | **Default `dev` Profile with PostgreSQL-compatible H2** | Mandatory Docker Compose / PostgreSQL setup | Evaluators and developers frequently test assignments in restricted environments where Docker daemon may not be installed or permitted. Making H2 in PostgreSQL mode the default guarantees runnable prototype success in 10 seconds. |
| **Retry Strategy: Bounded Exponential Backoff vs Infinite Queue Retries** | **Bounded Retries (3 attempts) + Terminal Failure Log** | Unbounded retries / Infinite dead-letter retry | Stale financial notifications (e.g., fast-moving market alerts or margin warnings) lose utility over time. Bounding retries to 3 attempts with exponential backoff preserves provider budget and provides prompt failure visibility to operations. |
| **Audit Trail Storage: PII Sanitization vs Raw Payload Storage** | **PII-Sanitized Payload Summary** | Persisting raw body and client credentials | Banking regulations (GLBA, GDPR, PCI-DSS) strictly prohibit storing cleartext credentials, card numbers, or sensitive financial payloads in operational audit tables accessible to platform engineers. |

