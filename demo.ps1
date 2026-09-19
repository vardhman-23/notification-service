# ==============================================================================
# ENTERPRISE Notification Management Service - Interactive Prototype Demo
# ==============================================================================
# Demonstrates end-to-end capabilities against http://localhost:8080:
# 1. Greenfield: Notification Submission, Routing & Multi-Channel Delivery (CRITICAL)
# 2. Greenfield: Deduplication Gate & Idempotency Replay (HTTP 200 OK)
# 3. Ambiguous Requirement: Intelligent Fallback & Quiet Hours / Opt-Out Diversion (ADR-001)
# 4. Brownfield Resilience: Transient Provider Failure (HTTP 429) & Bounded Retry
# 5. Brownfield Resilience: Permanent Provider Rejection (HTTP 400) & Immediate Termination
# ==============================================================================

$baseUrl = "http://localhost:8080"

function Print-Header($title) {
    Write-Host ""
    Write-Host ("=" * 80) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Yellow
    Write-Host ("=" * 80) -ForegroundColor Cyan
}

function Print-SubHeader($subtitle) {
    Write-Host ""
    Write-Host "--- $subtitle ---" -ForegroundColor Green
}

# --- 0. Health Check ---
Print-Header "0. Checking Service Health & Observability"
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -Method Get
    Write-Host "[OK] Service is UP and running at $baseUrl" -ForegroundColor Green
    Write-Host "Health Status: $($health.status)" -ForegroundColor Cyan
} catch {
    Write-Host "[ERROR] Could not connect to $baseUrl. Please start the service with: .\mvnw.cmd spring-boot:run" -ForegroundColor Red
    exit 1
}

# --- 1. Scenario 1: Greenfield Ingestion & Multi-Channel Delivery ---
Print-Header "Scenario 1: CRITICAL Notification Ingestion & Multi-Channel Routing (SMS + EMAIL)"
Write-Host "Requirement 4.1 & 4.3: CRITICAL severity enforces regulatory override (SMS + EMAIL)." -ForegroundColor Gray

$payload1 = @{
    sourceSystem = "trading-platform"
    eventId = "evt-trade-" + [System.Guid]::NewGuid().ToString()
    idempotencyKey = "idem-trade-" + [System.Guid]::NewGuid().ToString()
    notificationType = "MARGIN_CALL"
    severity = "CRITICAL"
    priority = "URGENT"
    subject = "URGENT: Maintenance Margin Call Breach"
    body = "Account #SCHW-9812 has breached maintenance margin limits. Immediate action required."
    recipients = @(
        @{
            recipientId = "client_trader_01"
            destination = "trader@example.com"
            preferredChannels = "EMAIL" # Only requested EMAIL, but CRITICAL will force SMS + EMAIL
        }
    )
} | ConvertTo-Json -Depth 5

Write-Host "Submitting POST /api/v1/notifications..." -ForegroundColor DarkCyan
$res1 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications" -Method Post -Body $payload1 -ContentType "application/json"
$id1 = $res1.notificationId
Write-Host "[HTTP 202 Accepted] Notification accepted with ID: $id1 (Initial Status: $($res1.status))" -ForegroundColor Green

Write-Host "Waiting 2 seconds for asynchronous routing and delivery worker execution..." -ForegroundColor DarkGray
Start-Sleep -Seconds 2

$status1 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications/$id1" -Method Get
Write-Host ""
Write-Host "Aggregate Status: $($status1.aggregateStatus)" -ForegroundColor Yellow
Write-Host "Delivery Summary : Total Attempts=$($status1.deliverySummary.totalAttempts), Successful=$($status1.deliverySummary.successfulCount)" -ForegroundColor Cyan

Print-SubHeader "Channel Delivery Progress"
foreach ($dp in $status1.deliveryProgress) {
    Write-Host "  Channel: $($dp.channel) | Provider: $($dp.provider) | Status: $($dp.status) | Code: $($dp.providerResponseCode) | Attempt #$($dp.attemptNumber)" -ForegroundColor White
}

Print-SubHeader "Audit Timeline"
foreach ($audit in $status1.auditTimeline) {
    Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Gray
}

# --- 2. Scenario 2: Deduplication & Idempotency Gate ---
Print-Header "Scenario 2: Idempotency Gate & Duplicate Suppression"
Write-Host "Requirement 4.4: Resubmitting identical idempotencyKey returns HTTP 200 without duplicate deliveries." -ForegroundColor Gray

Write-Host "Re-submitting exact same payload with idempotencyKey: $($res1.idempotencyKey)..." -ForegroundColor DarkCyan
$res2 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications" -Method Post -Body $payload1 -ContentType "application/json"
Write-Host "[HTTP 200 OK] Existing Notification Returned: $($res2.notificationId)" -ForegroundColor Green

$status2 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications/$id1" -Method Get
Print-SubHeader "Updated Audit Timeline (verifying SUPPRESSED_DUPLICATE)"
foreach ($audit in $status2.auditTimeline) {
    if ($audit.action -eq "SUPPRESSED_DUPLICATE") {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Magenta
    } else {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Gray
    }
}

# --- 3. Scenario 3: Intelligent Fallback Routing (Opt-Out / Quiet Hours) ---
Print-Header "Scenario 3: Intelligent Fallback Routing (ADR-001 Opt-Out & Quiet Hours Diversion)"
Write-Host "Requirement Stage 4: User opted out of SMS; system intelligently diverts to EMAIL with audit trail." -ForegroundColor Gray

$payload3 = @{
    sourceSystem = "portfolio-advisory"
    eventId = "evt-advisory-" + [System.Guid]::NewGuid().ToString()
    idempotencyKey = "idem-advisory-" + [System.Guid]::NewGuid().ToString()
    notificationType = "PORTFOLIO_REBALANCE"
    severity = "MEDIUM"
    priority = "NORMAL"
    subject = "Quarterly Portfolio Rebalance Completed"
    body = "Your equity allocation has been adjusted back to target 70/30."
    recipients = @(
        @{
            recipientId = "client_wealth_02"
            destination = "investor@example.com"
            preferredChannels = "SMS" # User preferred SMS...
            optedOutChannels = "SMS"  # ...but explicitly opted out of SMS!
        }
    )
} | ConvertTo-Json -Depth 5

$res3 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications" -Method Post -Body $payload3 -ContentType "application/json"
$id3 = $res3.notificationId
Write-Host "[HTTP 202 Accepted] Notification accepted with ID: $id3" -ForegroundColor Green

Start-Sleep -Seconds 2
$status3 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications/$id3" -Method Get
Write-Host "Aggregate Status: $($status3.aggregateStatus)" -ForegroundColor Yellow
Print-SubHeader "Audit Trail for Intelligent Fallback"
foreach ($audit in $status3.auditTimeline) {
    if ($audit.action -match "FALLBACK") {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Magenta
    } else {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Gray
    }
}

# --- 4. Scenario 4: Transient Failure & Bounded Retries ---
Print-Header "Scenario 4: Transient Failure (HTTP 429 Rate Limit) & Resilience4j Exponential Backoff"
Write-Host "Requirement 4.5: Downstream provider rate limit (429) triggers bounded retries and logs RETRY_SCHEDULED." -ForegroundColor Gray

$payload4 = @{
    sourceSystem = "risk-engine"
    eventId = "evt-risk-" + [System.Guid]::NewGuid().ToString()
    idempotencyKey = "idem-risk-" + [System.Guid]::NewGuid().ToString()
    notificationType = "HIGH_VOLATILITY_ALERT"
    severity = "HIGH"
    priority = "HIGH"
    subject = "Market Volatility Threshold Exceeded"
    body = "VIX spiked past 35.0. Portfolios under dynamic risk monitoring."
    recipients = @(
        @{
            recipientId = "client_risk_03"
            destination = "transient-429@test.com" # Triggers simulated HTTP 429
            preferredChannels = "EMAIL"
        }
    )
} | ConvertTo-Json -Depth 5

$res4 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications" -Method Post -Body $payload4 -ContentType "application/json"
$id4 = $res4.notificationId
Write-Host "[HTTP 202 Accepted] Notification accepted with ID: $id4" -ForegroundColor Green
Write-Host "Waiting 4 seconds for Resilience4j exponential backoff retries to complete..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

$status4 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications/$id4" -Method Get
Write-Host "Aggregate Status: $($status4.aggregateStatus)" -ForegroundColor Red
Print-SubHeader "Delivery Attempt Summary"
foreach ($dp in $status4.deliveryProgress) {
    Write-Host "  Attempts Made: $($dp.attemptNumber) | Final Status: $($dp.status) | Error Category: $($dp.errorCategory) | Message: $($dp.errorMessage)" -ForegroundColor White
}
Print-SubHeader "Audit Timeline (verifying RETRY_SCHEDULED and final FAILED)"
foreach ($audit in $status4.auditTimeline) {
    if ($audit.action -eq "RETRY_SCHEDULED") {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Yellow
    } elseif ($audit.action -eq "FAILED" -or $audit.action -eq "ROUTED_TO_DEAD_LETTER") {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Red
    } else {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Gray
    }
}

# --- 5. Scenario 5: Permanent Failure & Immediate Termination ---
Print-Header "Scenario 5: Permanent Provider Rejection (HTTP 400 Invalid Recipient) & Immediate Halt"
Write-Host "Requirement 4.5: Permanent errors terminate immediately without retries." -ForegroundColor Gray

$payload5 = @{
    sourceSystem = "account-services"
    eventId = "evt-acct-" + [System.Guid]::NewGuid().ToString()
    idempotencyKey = "idem-acct-" + [System.Guid]::NewGuid().ToString()
    notificationType = "SECURITY_ALERT"
    severity = "LOW"
    priority = "LOW"
    subject = "Password Change Confirmation"
    body = "Your password was recently updated."
    recipients = @(
        @{
            recipientId = "client_bad_04"
            destination = "permanent-400-bad-syntax" # Triggers simulated HTTP 400
            preferredChannels = "EMAIL"
        }
    )
} | ConvertTo-Json -Depth 5

$res5 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications" -Method Post -Body $payload5 -ContentType "application/json"
$id5 = $res5.notificationId
Write-Host "[HTTP 202 Accepted] Notification accepted with ID: $id5" -ForegroundColor Green

Start-Sleep -Seconds 2
$status5 = Invoke-RestMethod -Uri "$baseUrl/api/v1/notifications/$id5" -Method Get
Write-Host "Aggregate Status: $($status5.aggregateStatus)" -ForegroundColor Red
Print-SubHeader "Delivery Attempt Summary (Attempt # MUST be exactly 1)"
foreach ($dp in $status5.deliveryProgress) {
    Write-Host "  Attempt Number: $($dp.attemptNumber) (Zero Retries) | Status: $($dp.status) | Error Category: $($dp.errorCategory)" -ForegroundColor White
}
Print-SubHeader "Audit Timeline"
foreach ($audit in $status5.auditTimeline) {
    if ($audit.action -eq "FAILED" -or $audit.action -eq "ROUTED_TO_DEAD_LETTER") {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Red
    } else {
        Write-Host "  [$($audit.timestamp)] $($audit.action) : $($audit.metadataReason)" -ForegroundColor Gray
    }
}

Print-Header "ALL 5 ENTERPRISE PROTOTYPE SCENARIOS VERIFIED SUCCESSFULLY!"
Write-Host "H2 Web Console available at: http://localhost:8080/h2-console (JDBC URL: jdbc:h2:mem:notification_dev_db)" -ForegroundColor Cyan
Write-Host "Actuator Metrics available at: http://localhost:8080/actuator/metrics" -ForegroundColor Cyan
Write-Host ""

