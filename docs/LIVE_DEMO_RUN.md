# Live Verification & End-to-End Demo Execution Artifact

**Project**: Enterprise Notification Management Service  
**Timestamp**: September 19, 2026  
**Environment**: Local In-Memory Development Profile (`application-dev.yml`)  
**Base URL**: `http://localhost:8080`  
**Execution Script**: [`demo.ps1`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.ps1) (PowerShell) / [`demo.sh`](file:///c:/Users/jainv/.gemini/antigravity/scratch/notification-service/demo.sh) (Bash)  
**Verification Result**: **ALL 5 SCENARIOS PASSED (100% EMPIRICAL SUCCESS)**  

---

## 1. Complete Live Terminal Execution Trace

```text
0. Checking Service Health & Observability
================================================================================
[OK] Service is UP and running at http://localhost:8080
Health Status: UP

================================================================================
  Scenario 1: CRITICAL Notification Ingestion & Multi-Channel Routing (SMS + EMAIL)
================================================================================
Requirement 4.1 & 4.3: CRITICAL severity enforces regulatory override (SMS + EMAIL).
Submitting POST /api/v1/notifications...
[HTTP 202 Accepted] Notification accepted with ID: 61813652-79ea-4372-9d2f-4999818ce6ad (Initial Status: ACCEPTED)
Waiting 2 seconds for asynchronous routing and delivery worker execution...

Aggregate Status: DELIVERED
Delivery Summary : Total Attempts=2, Successful=2

--- Channel Delivery Progress ---
  Channel: EMAIL | Provider: AWS_SES | Status: SENT | Code: 250_OK | Attempt #1
  Channel: SMS | Provider: TWILIO | Status: SENT | Code: 200_DELIVERED | Attempt #1

--- Audit Timeline ---
  [09/19/2026 15:45:59] ACCEPTED : Notification accepted from source: trading-platform
  [09/19/2026 15:45:59] ROUTED : Severity CRITICAL forced channels [EMAIL, SMS] for recipient 'client_trader_01'
  [09/19/2026 15:45:59] DELIVERED : Delivered successfully to recipient 'client_trader_01' via EMAIL
  [09/19/2026 15:45:59] DELIVERED : Delivered successfully to recipient 'client_trader_01' via SMS

================================================================================
  Scenario 2: Idempotency Gate & Duplicate Suppression
================================================================================
Requirement 4.4: Resubmitting identical idempotencyKey returns HTTP 200 without duplicate deliveries.
Re-submitting exact same payload with idempotencyKey: idem-trade-12bb3c1c-8e4f-4788-8b27-f1024e71b73b...
[HTTP 200 OK] Existing Notification Returned: 61813652-79ea-4372-9d2f-4999818ce6ad

--- Updated Audit Timeline (verifying SUPPRESSED_DUPLICATE) ---
  [09/19/2026 15:45:59] ACCEPTED : Notification accepted from source: trading-platform
  [09/19/2026 15:45:59] ROUTED : Severity CRITICAL forced channels [EMAIL, SMS] for recipient 'client_trader_01'
  [09/19/2026 15:45:59] DELIVERED : Delivered successfully to recipient 'client_trader_01' via EMAIL
  [09/19/2026 15:45:59] DELIVERED : Delivered successfully to recipient 'client_trader_01' via SMS
  [09/19/2026 15:46:01] SUPPRESSED_DUPLICATE : Duplicate submission suppressed for idempotency key: idem-trade-12bb3c1c-8e4f-4788-8b27-f1024e71b73b

================================================================================
  Scenario 3: Intelligent Fallback Routing (ADR-001 Opt-Out & Quiet Hours Diversion)
================================================================================
Requirement Stage 4: User opted out of SMS; system intelligently diverts to EMAIL with audit trail.
[HTTP 202 Accepted] Notification accepted with ID: 1454723d-8a47-4de7-9daf-00417ac78af0
Aggregate Status: DELIVERED

--- Audit Trail for Intelligent Fallback ---
  [09/19/2026 15:46:01] ACCEPTED : Notification accepted from source: portfolio-advisory
  [09/19/2026 15:46:01] CHANNEL_FALLBACK_APPLIED : User opted out of requested channel 'SMS'. Applied intelligent fallback to EMAIL.
  [09/19/2026 15:46:01] ROUTED : Routing completed for recipient 'client_wealth_02'. Final channels: [EMAIL]
  [09/19/2026 15:46:01] DELIVERED : Delivered successfully to recipient 'client_wealth_02' via EMAIL

================================================================================
  Scenario 4: Transient Failure (HTTP 429 Rate Limit) & Resilience4j Exponential Backoff
================================================================================
Requirement 4.5: Downstream provider rate limit (429) triggers bounded retries and logs RETRY_SCHEDULED.
[HTTP 202 Accepted] Notification accepted with ID: b0ca1bb0-3b16-42d3-a36f-8c85c874762c
Waiting 4 seconds for Resilience4j exponential backoff retries to complete...
Aggregate Status: DEAD_LETTER

--- Delivery Attempt Summary ---
  Attempts Made: 4 | Final Status: FAILED | Error Category: RATE_LIMIT_EXCEEDED | Message: Unexpected failure: Exhausted retries: Rate limit exceeded by AWS_SES (HTTP 429)

--- Audit Timeline (verifying RETRY_SCHEDULED and final FAILED) ---
  [09/19/2026 15:46:03] ACCEPTED : Notification accepted from source: risk-engine
  [09/19/2026 15:46:03] ROUTED : Routing completed for recipient 'client_risk_03'. Final channels: [EMAIL]
  [09/19/2026 15:46:03] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #1): Rate limit exceeded by AWS_SES (HTTP 429)
  [09/19/2026 15:46:03] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #2): Rate limit exceeded by AWS_SES (HTTP 429)
  [09/19/2026 15:46:04] RETRY_SCHEDULED : Retry scheduled for recipient 'client_risk_03' on EMAIL (attempt #3): Rate limit exceeded by AWS_SES (HTTP 429)
  [09/19/2026 15:46:04] FAILED : Delivery failed after exhausting retries for recipient 'client_risk_03' on EMAIL: Rate limit exceeded by AWS_SES (HTTP 429)
  [09/19/2026 15:46:04] ROUTED_TO_DEAD_LETTER : All delivery attempts failed (1/1). Routed notification to Dead Letter Queue for compliance audit.

================================================================================
  Scenario 5: Permanent Provider Rejection (HTTP 400 Invalid Recipient) & Immediate Halt
================================================================================
Requirement 4.5: Permanent errors terminate immediately without retries.
[HTTP 202 Accepted] Notification accepted with ID: 2a70c733-4bad-4a96-8143-4d08fe266e94
Aggregate Status: DEAD_LETTER

--- Delivery Attempt Summary (Attempt # MUST be exactly 1) ---
  Attempt Number: 1 (Zero Retries) | Status: FAILED | Error Category: INVALID_RECIPIENT

--- Audit Timeline ---
  [09/19/2026 15:46:07] ACCEPTED : Notification accepted from source: account-services
  [09/19/2026 15:46:07] ROUTED : Routing completed for recipient 'client_bad_04'. Final channels: [EMAIL]
  [09/19/2026 15:46:07] FAILED : Permanent provider rejection (HTTP 400) for recipient 'client_bad_04' on EMAIL: Invalid recipient email syntax (HTTP 400)
  [09/19/2026 15:46:07] ROUTED_TO_DEAD_LETTER : All delivery attempts failed (1/1). Routed notification to Dead Letter Queue for compliance audit.

================================================================================
  ALL 5 ENTERPRISE PROTOTYPE SCENARIOS VERIFIED SUCCESSFULLY!
================================================================================
H2 Web Console available at: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:mem:notification_dev_db)
Actuator Metrics available at: http://localhost:8080/actuator/metrics
```

---

## 2. Invariants Empirically Validated

| Invariant | Scenario | Expected Architectural Behavior | Empirical Result |
|---|---|---|---|
| **Regulatory Multi-Channel Override** | 1 | CRITICAL alerts force both SMS and EMAIL regardless of standard preference. | **Verified**: Both AWS SES and Twilio dispatches executed; aggregate status `DELIVERED`. |
| **Strict Composite Idempotency** | 2 | Duplicate submission with same `(source_system, event_id, idempotency_key)` returns HTTP 200 without creating new delivery attempts. | **Verified**: HTTP 200 returned with existing ID; `SUPPRESSED_DUPLICATE` recorded in audit trail. |
| **Intelligent Opt-Out Fallback (ADR-001)** | 3 | When recipient has opted out of SMS, alert diverts automatically to EMAIL. | **Verified**: `CHANNEL_FALLBACK_APPLIED` audit event; delivered via EMAIL. |
| **Bounded Resilience4j Retry & DLQ** | 4 | Downstream HTTP 429 rate limiting triggers bounded exponential backoff (max 3 retries), routing to `DEAD_LETTER` upon exhaustion. | **Verified**: 3 `RETRY_SCHEDULED` audit events logged; status `DEAD_LETTER`; `ROUTED_TO_DEAD_LETTER` logged. |
| **Permanent Rejection Short-Circuit** | 5 | Unrecoverable client error (HTTP 400 bad syntax) terminates on attempt #1 with zero retries. | **Verified**: Exactly 1 attempt made; immediate `ROUTED_TO_DEAD_LETTER` to DLQ. |
