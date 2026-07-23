-- Escalation exhaustion tracking.
--
-- When the worker delivers the final step of a policy and no step remains, it stamps
-- escalation_exhausted_at. A sweeper later flips such incidents to DEAD_LETTERED once a grace
-- period has passed — deliberately not immediately, so an on-call who acknowledges seconds after
-- the last page is not rejected by an already-terminal status.

ALTER TABLE incident ADD COLUMN escalation_exhausted_at TIMESTAMPTZ;

-- Backs the sweeper's lookup; partial so it stays tiny (only unacked, exhausted incidents).
CREATE INDEX idx_incident_exhausted_open
    ON incident (escalation_exhausted_at)
    WHERE status = 'OPEN' AND escalation_exhausted_at IS NOT NULL;
