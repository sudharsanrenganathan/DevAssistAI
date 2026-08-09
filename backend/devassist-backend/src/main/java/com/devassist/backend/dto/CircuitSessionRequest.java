package com.devassist.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Create/update payload for a saved Circuit session. payload arrives as a
 * JSON value from the frontend and is stored serialized; null fields on
 * update mean "leave unchanged".
 */
public record CircuitSessionRequest(
        String title,
        JsonNode payload,
        Integer subCount) {
}
