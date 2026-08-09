package com.devassist.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devassist.backend.dto.CircuitSessionRequest;
import com.devassist.backend.service.CircuitSessionNotFoundException;
import com.devassist.backend.service.CircuitSessionService;

/**
 * Saved Circuit sessions, scoped to the authenticated user. Requires a valid
 * Supabase JWT (all of /api/circuit/** is authenticated in SecurityConfig);
 * the owner is always the token's email — never trusted from the request body.
 */
@RestController
@RequestMapping("/api/circuit/sessions")
public class CircuitSessionController {

    private final CircuitSessionService sessionService;

    public CircuitSessionController(CircuitSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CircuitSessionRequest request,
                                    Authentication authentication) {
        return ResponseEntity.ok(sessionService.create(authentication.getName(), request));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) {
        return ResponseEntity.ok(sessionService.list(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id, Authentication authentication) {
        try {
            return ResponseEntity.ok(sessionService.get(authentication.getName(), id));
        } catch (CircuitSessionNotFoundException e) {
            return notFound();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody CircuitSessionRequest request,
                                    Authentication authentication) {
        try {
            return ResponseEntity.ok(sessionService.update(authentication.getName(), id, request));
        } catch (CircuitSessionNotFoundException e) {
            return notFound();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        try {
            sessionService.delete(authentication.getName(), id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (CircuitSessionNotFoundException e) {
            return notFound();
        }
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "error", "message", "Circuit not found."));
    }
}
