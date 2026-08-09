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

import com.devassist.backend.dto.IgniteProjectRequest;
import com.devassist.backend.service.IgniteProjectService;
import com.devassist.backend.service.ProjectNotFoundException;

/**
 * Saved Ignite projects, scoped to the authenticated user. Requires a valid
 * Supabase JWT (not in SecurityConfig's permit-all list); the owner is always
 * the token's email — never trusted from the request body.
 */
@RestController
@RequestMapping("/api/project-builder/projects")
public class IgniteProjectController {

    private final IgniteProjectService projectService;

    public IgniteProjectController(IgniteProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody IgniteProjectRequest request,
                                    Authentication authentication) {
        return ResponseEntity.ok(projectService.create(authentication.getName(), request));
    }

    @GetMapping
    public ResponseEntity<?> list(Authentication authentication) {
        return ResponseEntity.ok(projectService.list(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id, Authentication authentication) {
        try {
            return ResponseEntity.ok(projectService.get(authentication.getName(), id));
        } catch (ProjectNotFoundException e) {
            return notFound();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody IgniteProjectRequest request,
                                    Authentication authentication) {
        try {
            return ResponseEntity.ok(projectService.update(authentication.getName(), id, request));
        } catch (ProjectNotFoundException e) {
            return notFound();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication authentication) {
        try {
            projectService.delete(authentication.getName(), id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (ProjectNotFoundException e) {
            return notFound();
        }
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "error", "message", "Project not found."));
    }
}
