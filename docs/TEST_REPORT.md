# Automated Test Execution & Code Coverage Report

**Project**: Enterprise Notification Management Service  
**Timestamp**: September 2026  
**Build Tool**: Apache Maven Wrapper (`mvnw`)  
**JDK Version**: Java 21 LTS  
**JaCoCo Version**: 0.8.15  
**Threshold Required**: Minimum Line Coverage $\ge$ 97.00%  

---

## 1. Executive Test Summary

| Metric | Result | Status |
|---|---|---|
| **Total Test Suites** | 23 Test Classes | Passed |
| **Total Tests Run** | 107 | Passed |
| **Failures** | 0 | Passed |
| **Errors** | 0 | Passed |
| **Skipped** | 4 (Linux Docker Testcontainers) | Expected |
| **Total Instructions Covered** | 98.7% | Passed |
| **Total Lines Covered** | **98.88%** (968 of 979 lines) | **Exceeds 97% Rule** |
| **Total Branches Covered** | **86.33%** (221 of 256 branches) | Passed |
| **Maven Verification** | `BUILD SUCCESS` | Exit Code 0 |

---

## 2. Test Suite Execution Breakdown

| Test Class | Layer / Component Tested | Tests Run | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| `GlobalExceptionHandlerTest` | RFC 7807 ProblemDetail & Error Handling | 10 | 0 | 0 | 0 |
| `CorrelationIdFilterTest` | Cross-boundary MDC Distributed Tracing | 3 | 0 | 0 | 0 |
| `EmailChannelProviderTest` | AWS SES Provider & Transient/Perm Faults | 7 | 0 | 0 | 0 |
| `SmsChannelProviderTest` | Twilio SMS Provider & Fault Simulations | 7 | 0 | 0 | 0 |
| `ConcurrencyControlIntegrationTest` | 8-Thread Simultaneous Race Collision | 1 | 0 | 0 | 0 |
| `AsyncNotificationPipelineUnitTest` | Async Pipeline Event Handling & Dispatch | 4 | 0 | 0 | 0 |
| `DeliveryWorkerUnitTest` | Worker Execution & Scoped Transactions | 7 | 0 | 0 | 0 |
| `DeliveryWorkerResilienceTest` | Bounded Backoff Retries & Immediate Term. | 4 | 0 | 0 | 0 |
| `DomainModelAndEnumTest` | JPA Entities, Lifecycle Hooks, Enums | 11 | 0 | 0 | 0 |
| `FlywaySchemaMigrationTest` | Reproducible SQL DDL Schema Migrations | 1 | 0 | 0 | 0 |
| `IntelligentFallbackRoutingTest` | ADR-001 Intelligent Fallback Policy | 4 | 0 | 0 | 0 |
| `NotificationEndToEndIntegrationTest` | Greenfield, Brownfield & Ambiguous E2E | 5 | 0 | 0 | 0 |
| `NotificationEndToEndTestcontainersTest` | Real PostgreSQL 16 Testcontainers | 4 | 0 | 0 | 4 |
| `NotificationIngestionApiTest` | REST Ingestion API & Deduplication Gate | 3 | 0 | 0 | 0 |
| `NotificationMetricsAndHealthTest` | Micrometer Telemetry & Actuator Health | 3 | 0 | 0 | 0 |
| `NotificationQueryApiTest` | REST Query API & Aggregate Status DTOs | 2 | 0 | 0 | 0 |
| `NotificationServiceApplicationTests` | Spring Context Bootstrapping & DI | 6 | 0 | 0 | 0 |
| `RoutingServiceTest` | Channel Strategy Selection Engine | 3 | 0 | 0 | 0 |
| `NotificationIngestionServiceUnitTest` | Ingestion Validation & Idempotency Logic | 5 | 0 | 0 | 0 |
| `NotificationPersistenceServiceUnitTest` | Isolated `REQUIRES_NEW` Persistence | 1 | 0 | 0 | 0 |
| `NotificationQueryServiceUnitTest` | Timeline Aggregation & Progress Builders | 3 | 0 | 0 | 0 |
| `RoutingServiceUnitTest` | Regulatory Overrides & Quiet Hour Fallbacks | 9 | 0 | 0 | 0 |
| `DataMaskingUtilsTest` | PII Sanitization (Emails, Phones, Tokens) | 4 | 0 | 0 | 0 |
| **Total** | | **107** | **0** | **0** | **4** |

---

## 3. JaCoCo Coverage Breakdown by Package

Generated from `target/site/jacoco/jacoco.csv`:

| Package | Total Lines | Covered Lines | Line Coverage | Total Branches | Branch Coverage |
|---|---|---|---|---|---|
| `com.demo.notification.api.controller` | 26 | 26 | **100.00%** | 2 | 100.00% |
| `com.demo.notification.api.dto` | 42 | 42 | **100.00%** | 0 | N/A |
| `com.demo.notification.api.error` | 64 | 63 | **98.44%** | 10 | 90.00% |
| `com.demo.notification.api.filter` | 17 | 17 | **100.00%** | 2 | 100.00% |
| `com.demo.notification.channel` | 55 | 55 | **100.00%** | 16 | 93.75% |
| `com.demo.notification.delivery` | 175 | 171 | **97.71%** | 38 | 84.21% |
| `com.demo.notification.domain.model` | 92 | 92 | **100.00%** | 14 | 85.71% |
| `com.demo.notification.domain.types` | 38 | 38 | **100.00%** | 0 | N/A |
| `com.demo.notification.observability` | 68 | 68 | **100.00%** | 6 | 100.00% |
| `com.demo.notification.service` | 344 | 338 | **98.26%** | 148 | 85.81% |
| `com.demo.notification.util` | 58 | 58 | **100.00%** | 20 | 90.00% |
| **Total Project** | **979** | **968** | **98.88%** | **256** | **86.33%** |

---

## 4. Empirical Verification Command

To reproduce this verification report locally:
```powershell
# Windows
.\mvnw.cmd clean verify

# Linux / macOS
./mvnw clean verify
```
HTML visual coverage report is generated at: `target/site/jacoco/index.html`.

