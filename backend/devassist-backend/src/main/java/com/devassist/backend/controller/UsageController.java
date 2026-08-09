package com.devassist.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.ProjectBuilderService;

/**
 * Exposes the authenticated user's daily Ignite token usage for the frontend
 * meter. Requires a valid Supabase JWT (not in SecurityConfig's permit-all list).
 */
@RestController
public class UsageController {

    private final ProjectBuilderService projectBuilderService;

    public UsageController(ProjectBuilderService projectBuilderService) {
        this.projectBuilderService = projectBuilderService;
    }

    @GetMapping("/api/usage")
    public ResponseEntity<?> getUsage(Authentication authentication) {
        try {
            String usageJson = projectBuilderService.getUsage(authentication.getName());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(usageJson);
        } catch (AiEngineException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "error",
                    "message", "Could not read token usage right now."));
        }
    }
}
