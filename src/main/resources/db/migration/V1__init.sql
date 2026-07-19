-- Escalade core schema.
-- Design notes worth defending in an interview:
--   * ux_incident_open_dedup enforces "one OPEN incident per (org, dedup_key)"
--     at the database, so a flaky monitor retrying a webhook cannot page twice.
--   * incident.version drives JPA optimistic locking: an ack that lands at the
--     same instant the worker fires the next step will collide on version.
--   * notification_attempt is the worker's work queue; idx_attempt_due backs the
--     `WHERE status='PENDING' AND due_at <= now()` claim query (SKIP LOCKED).

CREATE TABLE organization (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    api_key    TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE escalation_policy (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_policy_org ON escalation_policy(org_id);

CREATE TABLE escalation_step (
    id            UUID PRIMARY KEY,
    policy_id     UUID NOT NULL REFERENCES escalation_policy(id) ON DELETE CASCADE,
    step_order    INT  NOT NULL,
    channel       TEXT NOT NULL,          -- EMAIL / SLACK / WEBHOOK
    target        TEXT NOT NULL,          -- email address / webhook url / channel id
    delay_seconds INT  NOT NULL DEFAULT 0,-- wait before THIS step, relative to the previous step firing
    UNIQUE (policy_id, step_order)
);
CREATE INDEX idx_step_policy ON escalation_step(policy_id);

CREATE TABLE incident (
    id           UUID PRIMARY KEY,
    org_id       UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    policy_id    UUID NOT NULL REFERENCES escalation_policy(id),
    title        TEXT NOT NULL,
    dedup_key    TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN / ACKNOWLEDGED / RESOLVED / DEAD_LETTERED
    current_step INT  NOT NULL DEFAULT 0,
    version      BIGINT NOT NULL DEFAULT 0,      -- optimistic lock (JPA @Version)
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    acked_at     TIMESTAMPTZ,
    resolved_at  TIMESTAMPTZ
);
CREATE INDEX idx_incident_org_status ON incident(org_id, status);
CREATE UNIQUE INDEX ux_incident_open_dedup ON incident(org_id, dedup_key) WHERE status = 'OPEN';

CREATE TABLE notification_attempt (
    id            UUID PRIMARY KEY,
    incident_id   UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    step_order    INT  NOT NULL,
    channel       TEXT NOT NULL,
    target        TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'PENDING', -- PENDING / SENT / FAILED / CANCELLED
    attempt_count INT  NOT NULL DEFAULT 0,
    due_at        TIMESTAMPTZ NOT NULL,
    sent_at       TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempt_due ON notification_attempt(status, due_at);
CREATE INDEX idx_attempt_incident ON notification_attempt(incident_id);

CREATE TABLE dead_letter (
    id                      UUID PRIMARY KEY,
    notification_attempt_id UUID NOT NULL REFERENCES notification_attempt(id) ON DELETE CASCADE,
    incident_id             UUID NOT NULL REFERENCES incident(id) ON DELETE CASCADE,
    reason                  TEXT NOT NULL,
    failed_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dead_letter_incident ON dead_letter(incident_id);
