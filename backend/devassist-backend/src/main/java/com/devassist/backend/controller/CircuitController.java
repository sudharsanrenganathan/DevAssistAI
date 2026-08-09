package com.devassist.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devassist.backend.dto.CircuitAskRequest;
import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.AiEngineRateLimitedException;
import com.devassist.backend.service.CircuitService;
import com.devassist.backend.service.RateLimitExceededException;

/**
 * HTTP entry point for Circuit (visual step-by-step answers). Requires a
 * valid Supabase JWT (enforced by SecurityConfig — not in the permit-all
 * list), because every ask charges the user's daily token budget.
 */
@RestController
@RequestMapping("/api/circuit")
public class CircuitController {

    private static final String FALLBACK_429_JSON =
            "{\"status\":\"busy\",\"message\":\"Circuit is busy right now. Please try again in a minute.\"}";

    private final CircuitService circuitService;

    public CircuitController(CircuitService circuitService) {
        this.circuitService = circuitService;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody CircuitAskRequest request,
                                 Authentication authentication) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "Please ask a question first."));
        }
        try {
            String circuitJson = circuitService.ask(authentication.getName(), request);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(circuitJson);
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("status", "busy", "message", e.getMessage()));
        } catch (AiEngineRateLimitedException e) {
            String body = e.getResponseBody();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body == null || body.isBlank() ? FALLBACK_429_JSON : body);
        } catch (AiEngineException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "error",
                    "message", "The AI engine had a problem drawing that circuit. Please try again."));
        }
    }
}
