#!/usr/bin/env bash
# ==============================================================================
# Charles Schwab Notification Management Service - Bash Verification Demo
# ==============================================================================
set -e

BASE_URL="http://localhost:8080"

echo "================================================================================"
echo "  Charles Schwab Notification Service Prototype - End-to-End Demo"
echo "================================================================================"

echo ""
echo "--- 0. Checking Health & Observability ---"
curl -s "$BASE_URL/actuator/health"
echo ""

echo ""
echo "--- Scenario 1: Greenfield Submission & Multi-Channel Delivery (CRITICAL) ---"
ID1=$(curl -s -X POST "$BASE_URL/api/v1/notifications" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "trading-platform",
    "eventId": "trade-evt-bash-1",
    "idempotencyKey": "idem-trade-bash-1",
    "notificationType": "MARGIN_CALL",
    "severity": "CRITICAL",
    "priority": "URGENT",
    "subject": "Margin Call Alert",
    "body": "Margin breach detected.",
    "recipients": [{"recipientId": "u1", "destination": "trader@schwab.com", "preferredChannels": "EMAIL"}]
  }' | grep -o '"notificationId":"[^"]*' | cut -d'"' -f4)

echo "Created Notification ID: $ID1"
sleep 2
curl -s "$BASE_URL/api/v1/notifications/$ID1" | grep -o '"aggregateStatus":"[^"]*'
echo ""

echo ""
echo "--- Scenario 2: Idempotency Replay (HTTP 200 OK) ---"
curl -s -o /dev/null -w "HTTP Status Code: %{http_code}\n" -X POST "$BASE_URL/api/v1/notifications" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "trading-platform",
    "eventId": "trade-evt-bash-1",
    "idempotencyKey": "idem-trade-bash-1",
    "notificationType": "MARGIN_CALL",
    "severity": "CRITICAL",
    "priority": "URGENT",
    "subject": "Margin Call Alert",
    "body": "Margin breach detected.",
    "recipients": [{"recipientId": "u1", "destination": "trader@schwab.com", "preferredChannels": "EMAIL"}]
  }'

echo ""
echo "--- Scenario 3: Intelligent Fallback Routing (Opt-out to Email) ---"
ID3=$(curl -s -X POST "$BASE_URL/api/v1/notifications" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "portfolio-service",
    "eventId": "port-evt-bash-1",
    "idempotencyKey": "idem-port-bash-1",
    "notificationType": "REBALANCE",
    "severity": "LOW",
    "priority": "NORMAL",
    "subject": "Portfolio Rebalanced",
    "body": "Portfolio has been rebalanced.",
    "recipients": [{"recipientId": "u2", "destination": "client@schwab.com", "preferredChannels": "SMS", "optedOutChannels": "SMS"}]
  }' | grep -o '"notificationId":"[^"]*' | cut -d'"' -f4)
sleep 2
curl -s "$BASE_URL/api/v1/notifications/$ID3" | grep -o '"metadataReason":"[^"]*'
echo ""

echo ""
echo "--- Scenario 4: Transient Error (HTTP 429) & Exponential Retry ---"
ID4=$(curl -s -X POST "$BASE_URL/api/v1/notifications" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "risk-engine",
    "eventId": "risk-evt-bash-1",
    "idempotencyKey": "idem-risk-bash-1",
    "notificationType": "RISK_ALERT",
    "severity": "HIGH",
    "priority": "HIGH",
    "subject": "Rate Limit Test",
    "body": "Testing HTTP 429.",
    "recipients": [{"recipientId": "u3", "destination": "transient-429@test.com", "preferredChannels": "EMAIL"}]
  }' | grep -o '"notificationId":"[^"]*' | cut -d'"' -f4)
sleep 4
curl -s "$BASE_URL/api/v1/notifications/$ID4" | grep -o '"errorCategory":"[^"]*'
echo ""

echo ""
echo "--- Scenario 5: Permanent Rejection (HTTP 400) & Immediate Termination ---"
ID5=$(curl -s -X POST "$BASE_URL/api/v1/notifications" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystem": "account-service",
    "eventId": "acct-evt-bash-1",
    "idempotencyKey": "idem-acct-bash-1",
    "notificationType": "AUTH_NOTICE",
    "severity": "LOW",
    "priority": "LOW",
    "subject": "Permanent Rejection Test",
    "body": "Testing HTTP 400.",
    "recipients": [{"recipientId": "u4", "destination": "permanent-400-bad-syntax", "preferredChannels": "EMAIL"}]
  }' | grep -o '"notificationId":"[^"]*' | cut -d'"' -f4)
sleep 2
curl -s "$BASE_URL/api/v1/notifications/$ID5" | grep -o '"attemptNumber":[0-9]*'
echo ""

echo "Demo completed successfully!"

