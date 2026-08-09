package com.devassist.backend.service;

/** The requested Circuit session does not exist or belongs to another user. */
public class CircuitSessionNotFoundException extends RuntimeException {

    public CircuitSessionNotFoundException(Long id) {
        super("Circuit session not found: " + id);
    }
}
