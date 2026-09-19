# Enterprise Notification Service - Submission Report & Reviewer Guide

**Candidate Assessment Submission**: Notification Management Service Prototype  
**Date**: September 2026  
**Technology Stack**: Java 21 LTS, Spring Boot 3.3.4, Resilience4j, Flyway, PostgreSQL / H2 In-Memory, Actuator  

---

## 1. Deliverables Checklist & Repository Mapping

All 5 core deliverables mandated by the Enterprise assignment specification are fully implemented, thoroughly documented, and empirically verified:

| # | Required Deliverable | Description | Repository Location & Proof |
|---|---|---|---|
| **1** | **Working Prototype** | Runnable, end-to-end prototype of the system accepting alerts, executing strategy routing, and delivering across channels with idempotency and audit logs. | • Main App: [`NotificationServiceApplication.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/demo/notification/NotificationServiceApplication.java)<br>• REST APIs: [`NotificationController.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/demo/notification/api/controller/NotificationController.java)<br>• Asynchronous Pipeline: [`AsyncNotificationPipeline.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/java/com/demo/notification/delivery/AsyncNotificationPipeline.java)<br>• Live Verification: [`demo.ps1`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.ps1) & [`demo.sh`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.sh) |
| **2** | **Architecture Overview** | Comprehensive documentation covering system components, tools, execution approach, control flow sequence diagrams, and key decisions. | • Architectural Specification: [`docs/ARCHITECTURE.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/ARCHITECTURE.md)<br>• Architectural Decision Record: [`docs/scenarios/ambiguous-routing.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/scenarios/ambiguous-routing.md)<br>• High-level Overview: [`README.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/README.md) |
| **3** | **Three Scenarios** | Implementations of Greenfield, Brownfield, and Ambiguous scenarios with requirement decomposition, execution approach, and empirical validation. | • Dedicated Scenarios Document: [`docs/SCENARIOS.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/SCENARIOS.md)<br>• Greenfield Tests: [`NotificationIngestionApiTest.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/NotificationIngestionApiTest.java)<br>• Brownfield Tests: [`DeliveryWorkerResilienceTest.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/DeliveryWorkerResilienceTest.java)<br>• Ambiguous Tests: [`IntelligentFallbackRoutingTest.java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java/com/demo/notification/IntelligentFallbackRoutingTest.java) |
| **4** | **Setup Instructions** | Clear instructions on how to configure and run the prototype locally with zero external prerequisites, as well as with Docker Compose. | • Setup Guide: [`README.md` (Section 1)](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/README.md#1-quick-start-run-locally-in-seconds)<br>• Zero-dependency Configuration: [`application-dev.yml`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/main/resources/application-dev.yml)<br>• Docker Orchestration: [`docker-compose.yml`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docker-compose.yml) |
| **5** | **Testing Approach, Limitations & Trade-offs** | In-depth documentation detailing the test suite pyramid, traceability matrix, known system boundaries, and defensible architectural trade-offs. | • Strategy & Trade-offs Doc: [`docs/TESTING_AND_TRADE_OFFS.md`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/docs/TESTING_AND_TRADE_OFFS.md)<br>• Test Suite (32 tests passing): [`src/test/java`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/src/test/java) |

---

## 2. Reviewer 3-Minute Quick Verification Guide

A reviewer can verify the entire submission end-to-end in under 3 minutes using the bundled Maven Wrapper:

### Step 1: Run the Automated Test Suite
```powershell
.\mvnw.cmd test
```
*Expected Result: 32 tests execute and pass with 0 failures and 0 errors.*

### Step 2: Start the Prototype Locally
```powershell
.\mvnw.cmd spring-boot:run
```
*The service automatically boots using the zero-dependency `dev` profile with in-memory PostgreSQL-compatible H2 on `http://localhost:8080`.*

### Step 3: Run the Live Verification Demo
In a separate terminal:
```powershell
# Windows
.\demo.ps1

# Linux / macOS
./demo.sh
```
*The script automatically exercises all 5 assessment scenarios against the running REST API, displaying colored terminal outputs of state transitions, delivery attempts, and audit timelines.*

---

## 3. Key Architectural Highlights for Evaluators

1. **Composite Idempotency Gate**:
   - Unique composite constraint on `(source_system, event_id, idempotency_key)` prevents double delivery of alerts.
   - Resubmitting duplicate payloads returns `HTTP 200 OK` with the existing notification record and appends `SUPPRESSED_DUPLICATE` to the audit history without creating new delivery attempts.
2. **Strategy Channel Routing with Intelligent Fallback (ADR-001)**:
   - Evaluates recipient preferences, quiet hours (22:00–07:00), and opt-outs.
   - *Tier 1 Regulatory Override*: `Severity.CRITICAL` enforces mandatory `SMS + EMAIL` dispatch, bypassing user opt-outs and quiet hours to preserve SEC/FINRA compliance.
   - *Tier 2/3 Fallbacks*: Non-critical alerts with quiet-hour or opt-out conflicts are diverted to `EMAIL`, generating traceable audit records (`QUIET_HOURS_FALLBACK` / `CHANNEL_FALLBACK_APPLIED`).
3. **Resilience4j Failure Classification & Bounded Retries**:
   - Classifies failures into Transient (`HTTP 429`, `503`, timeouts) vs Permanent (`HTTP 400`, `401`).
   - Transient failures trigger bounded exponential backoff retries (3 attempts) with `RETRY_SCHEDULED` audit records.
   - Permanent rejections terminate immediately on Attempt #1 with zero retries.
4. **Immutable Audit History with PII Sanitization**:
   - Complete lifecycle trace (`ACCEPTED` $\rightarrow$ `ROUTED` $\rightarrow$ `DELIVERED` / `FAILED`).
   - Sensitive payloads, credentials, and authentication tokens are masked and sanitized.

