-- Charles Schwab Notification Management Service
-- Schema Initialization V1 (Domain Model & Enums Alignment)

CREATE TABLE IF NOT EXISTS notifications (
    notification_id UUID PRIMARY KEY,
    source_system VARCHAR(100) NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128),
    notification_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    subject VARCHAR(255),
    body TEXT NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_notification_idempotency UNIQUE (source_system, event_id, idempotency_key)
);

CREATE INDEX idx_notifications_event_id ON notifications(event_id);
CREATE INDEX idx_notifications_idempotency ON notifications(idempotency_key);
CREATE INDEX idx_notifications_status ON notifications(status);
CREATE INDEX idx_notifications_created_at ON notifications(created_at);

CREATE TABLE IF NOT EXISTS notification_recipients (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    recipient_id VARCHAR(100) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    preferred_channels VARCHAR(255),
    opted_out_channels VARCHAR(255),
    quiet_hours_start INT,
    quiet_hours_end INT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recipients_notification_id ON notification_recipients(notification_id);
CREATE INDEX idx_recipients_recipient_id ON notification_recipients(recipient_id);

CREATE TABLE IF NOT EXISTS delivery_attempts (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    recipient_id VARCHAR(100) NOT NULL,
    channel VARCHAR(30) NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL,
    provider VARCHAR(50),
    provider_response_code VARCHAR(50),
    error_message TEXT,
    execution_time_ms BIGINT,
    error_category VARCHAR(50),
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_delivery_notification_id ON delivery_attempts(notification_id);
CREATE INDEX idx_delivery_status ON delivery_attempts(status);

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,
    metadata_reason VARCHAR(1000),
    sanitized_payload_summary VARCHAR(500),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_notification_id ON audit_logs(notification_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    notification_id UUID NOT NULL,
    source_system VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
