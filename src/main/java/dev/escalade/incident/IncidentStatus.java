package dev.escalade.incident;

public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    /** Automated escalation exhausted every step without an acknowledgement. */
    DEAD_LETTERED,
    RESOLVED;

    /**
     * Only RESOLVED is truly closed. DEAD_LETTERED means the engine gave up paging, not that the
     * incident is finished — a human can still acknowledge or resolve it afterwards.
     */
    public boolean isTerminal() {
        return this == RESOLVED;
    }
}
