package com.devassist.backend.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.backend.dto.ProjectEditRequest;
import com.devassist.backend.dto.ProjectFileRequest;
import com.devassist.backend.dto.ProjectPlanRequest;
import com.devassist.backend.service.AiEngineBusyException;
import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.ProjectBuilderService;
import com.devassist.backend.service.RateLimitExceededException;

/**
 * HTTP entry points for the Ignite project builder. Requires a valid Supabase
 * JWT (enforced by SecurityConfig — these paths are not in the permit-all list).
 */
@RestController
@RequestMapping("/api/project-builder")
public class ProjectBuilderController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectBuilderService projectBuilderService;

    public ProjectBuilderController(ProjectBuilderService projectBuilderService) {
        this.projectBuilderService = projectBuilderService;
    }

    @PostMapping("/plan")
    public ResponseEntity<?> createPlan(@RequestBody ProjectPlanRequest request,
                                        Authentication authentication) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "Please describe your project first."));
        }
        try {
            String planJson = projectBuilderService.createPlan(authentication.getName(), request);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(planJson);
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("status", "busy", "message", e.getMessage()));
        } catch (AiEngineBusyException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("status", "busy", "message", e.getMessage()));
        } catch (AiEngineException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "error",
                    "message", "The AI engine had a problem creating the plan. Please try again."));
        }
    }

    @PostMapping("/generate-file")
    public ResponseEntity<StreamingResponseBody> generateFile(@RequestBody ProjectFileRequest request,
                                                              Authentication authentication) {
        if (request.prompt() == null || request.prompt().isBlank()
                || request.filePath() == null || request.filePath().isBlank()) {
            return streamedJson(HttpStatus.BAD_REQUEST,
                    Map.of("status", "error", "message", "prompt and filePath are required."));
        }
        try {
            StreamingResponseBody body =
                    projectBuilderService.streamGeneratedFile(authentication.getName(), request);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
        } catch (RateLimitExceededException e) {
            return streamedJson(HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("status", "busy", "message", e.getMessage()));
        }
    }

    /**
     * The declared return type must be ResponseEntity<StreamingResponseBody> —
     * with a wildcard, Spring MVC routes the stream through message converters
     * instead of async streaming and the request blows up — so error JSON is
     * written as a tiny stream to keep the same wire format as the other routes.
     */
    private static ResponseEntity<StreamingResponseBody> streamedJson(HttpStatus status,
                                                                      Map<String, String> payload) {
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            json = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
        }
        byte[] body = json;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(out -> out.write(body));
    }

    @PostMapping("/edit")
    public ResponseEntity<?> applyEdit(@RequestBody ProjectEditRequest request,
                                       Authentication authentication) {
        if (request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "Please describe the change you want."));
        }
        try {
            String resultJson = projectBuilderService.applyEdit(authentication.getName(), request);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resultJson);
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("status", "busy", "message", e.getMessage()));
        } catch (AiEngineBusyException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("status", "busy", "message", e.getMessage()));
        } catch (AiEngineException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "error",
                    "message", "The AI engine had a problem applying that change. Please try again."));
        }
    }
}
