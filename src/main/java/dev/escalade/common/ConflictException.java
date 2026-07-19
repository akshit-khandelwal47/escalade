package dev.escalade.common;

/** Invalid state transition (e.g. acking an already-resolved incident). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
