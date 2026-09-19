# ADR-001: Conflict Resolution and Intelligent Fallback Policy for Channel Routing

- **Status**: Accepted / Approved
- **Deciders**: Architecture Review Board, Compliance & Risk Committee, Platform Engineering
- **Date**: 2026-09-18
- **Context**: Enterprise Notification Management Service

---

## 1. Context and Problem Statement

In an enterprise financial notification ecosystem, alert requests arrive from diverse upstream systems (Trading Platforms, Risk Engines, Portfolio Services, Marketing Systems). A frequent ambiguity arises when **source system channel requirements directly conflict with recipient privacy preferences, opt-outs, or quiet-hour restrictions**.

### Conflicting Scenarios:
1. **Source System Demands Channel vs. User Opt-Out**:
   - An upstream system requests delivery via `SMS`, but the customer has explicitly opted out of text notifications (e.g. TCPA compliance).
2. **Quiet-Hour Window Conflicts**:
   - An alert is submitted at 02:30 AM local recipient time requesting intrusive channels (`SMS`), but the recipient configured quiet hours between 22:00 and 07:00.
3. **Regulatory Duty-of-Care vs. User Preference**:
   - A critical Margin Call or Account Compromise alert occurs during quiet hours for an account where SMS is opted out. Dropping the alert or delaying it exposes the firm and client to immense financial and regulatory liability.

---

## 2. Decision Drivers

- **Regulatory Compliance (FINRA / SEC)**: Mandatory and timely delivery of critical operational, margin, and security alerts.
- **Consumer Protection (TCPA / FCC)**: Adherence to user opt-outs and prevention of unlawful, non-consensual intrusive contact.
- **Client Experience & Trust**: Respecting customer quiet hours for non-emergency notifications.
- **Auditability & Determinism**: Every routing decision, diversion, or override must leave an immutable, defensible audit record.

---

## 3. Considered Options

### Option A: Source System Always Wins (Override All)
- *Pros*: Simple implementation; ensures upstream messages are delivered as requested.
- *Cons*: Severe violation of TCPA regulations; destroys user trust; high spam complaints and legal risk.

### Option B: User Preference Always Wins (Strict Drop)
- *Pros*: Strict privacy and TCPA adherence.
- *Cons*: Regulatory failure if critical margin calls or security alerts are suppressed; severe capital loss exposure.

### Option C: Rejection with HTTP 422 Unprocessable Entity
- *Pros*: Defers decision to upstream caller.
- *Cons*: Tight coupling; upstream business systems have no awareness of client timezones or quiet hours; creates cascading failure.

### Option D: Hierarchical "Intelligent Fallback" Policy (Chosen)
- Establishes a transparent, multi-tier deterministic policy governing priority, regulatory escalation, quiet-hour diversion, and opt-out fallback.

---

## 4. Decision: Hierarchical Intelligent Fallback Engine

We adopt **Option D (Hierarchical Intelligent Fallback)** with the following precedence tiers:

```mermaid
flowchart TD
    Start[Notification Ingested for Routing] --> CheckSeverity{Severity == CRITICAL?}

    CheckSeverity -- YES --> RegOverride["Tier 1: Regulatory Override
    - Force EMAIL + SMS
    - Bypass Quiet Hours & Opt-Outs
    - AuditLog: REGULATORY_OVERRIDE_APPLIED"]
    
    CheckSeverity -- NO --> CheckOptOut{Requested Channel Opted Out?}
    
    CheckOptOut -- YES --> OptOutFallback["Tier 3: Opt-Out Fallback
    - Divert to EMAIL
    - AuditLog: CHANNEL_FALLBACK_APPLIED"]
    
    CheckOptOut -- NO --> CheckQuietHours{In Quiet Hours & Intrusive (SMS)?}
    
    CheckQuietHours -- YES --> QuietFallback["Tier 2: Quiet Hours Fallback
    - Divert SMS to EMAIL
    - AuditLog: QUIET_HOURS_FALLBACK"]
    
    CheckQuietHours -- NO --> StandardRoute["Standard Delivery
    - Route to Requested Channel
    - AuditLog: ROUTED"]

    RegOverride --> Dispatch[Stage Delivery Attempts]
    OptOutFallback --> Dispatch
    QuietFallback --> Dispatch
    StandardRoute --> Dispatch
```

### Policy Rules:

### Tier 1: Regulatory & Emergency Override (`Severity.CRITICAL`)
- Alerts classified with `Severity.CRITICAL` (e.g. `MARGIN_CALL`, `FRAUD_ALERT`, `ACCOUNT_TAKEOVER`) represent legal and financial emergencies.
- **Action**: Mandatory multi-channel dispatch (`EMAIL` and `SMS`). Quiet-hour delays and non-statutory opt-outs are bypassed.
- **Compliance Audit**: Writes an `AuditLog` entry with action `REGULATORY_OVERRIDE_APPLIED`, citing FINRA Rule 4210 / SEC customer notification obligations.

### Tier 2: Quiet-Hour Diversion (`22:00 - 07:00`)
- Applies to non-critical alerts (`HIGH`, `MEDIUM`, `LOW`).
- Intrusive channels (`SMS`) active during the recipient's quiet-hour window are **intelligently diverted to non-intrusive, persistent channels (`EMAIL`)**.
- **Compliance Audit**: Writes an `AuditLog` entry with action `QUIET_HOURS_FALLBACK` detailing the diversion rationale.

### Tier 3: Explicit Channel Opt-Out Fallback
- When a non-critical alert requests a channel the user has opted out of:
  - If `SMS` is opted out $\rightarrow$ divert to `EMAIL`.
  - If both `EMAIL` and `SMS` are opted out $\rightarrow$ route to `IN_APP` or mark `SUPPRESSED_OPT_OUT`.
- **Compliance Audit**: Writes an `AuditLog` entry with action `CHANNEL_FALLBACK_APPLIED` specifying the source and target channels.

---

## 5. Consequences & Trade-offs

### Positive:
- **Zero Ambiguity**: Clear, deterministic resolution of conflicting inputs.
- **Legal Compliance**: Full adherence to TCPA while preserving FINRA/SEC regulatory integrity.
- **Enhanced Observability**: Financial auditors can trace every routing change through immutable audit entries.

### Negative / Mitigations:
- *Overhead*: Requires timezone/hour calculation and preference evaluation per recipient.
  - *Mitigation*: Evaluated in-memory during the routing phase; cached preferences prevent database latency.

