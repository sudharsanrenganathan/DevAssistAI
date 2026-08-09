package com.devassist.backend.dto;

/**
 * Request body for POST /api/circuit/ask. context carries the session's
 * recent conversation (plain text, optional) so follow-ups continue the
 * ongoing topic.
 */
public record CircuitAskRequest(String prompt, String context) {
}
