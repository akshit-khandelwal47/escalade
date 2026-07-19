package dev.escalade.incident;

public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED,
    DEAD_LETTERED;

    /** Terminal states never escalate again and reject further transitions. */
    public boolean isTerminal() {
        return this == RESOLVED || this == DEAD_LETTERED;
    }
}
