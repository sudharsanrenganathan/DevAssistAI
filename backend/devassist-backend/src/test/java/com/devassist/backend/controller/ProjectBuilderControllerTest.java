package com.devassist.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.devassist.backend.dto.ProjectEditRequest;
import com.devassist.backend.dto.ProjectFileRequest;
import com.devassist.backend.dto.ProjectPlanRequest;
import com.devassist.backend.service.AiEngineBusyException;
import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.ProjectBuilderService;
import com.devassist.backend.service.RateLimitExceededException;

@ExtendWith(MockitoExtension.class)
class ProjectBuilderControllerTest {

    private static final String USER = "user@example.com";

    @Mock
    private ProjectBuilderService projectBuilderService;

    private ProjectBuilderController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ProjectBuilderController(projectBuilderService);
        authentication = mock(Authentication.class);
    }

    private static ProjectFileRequest fileRequest(String prompt, String filePath) {
        return new ProjectFileRequest(prompt, "", filePath, "", List.of(), Map.of());
    }

    @Test
    void planRejectsBlankPromptWith400() {
        ResponseEntity<?> response = controller.createPlan(new ProjectPlanRequest("  "), authentication);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void planReturnsEngineJsonOnSuccess() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.createPlan(eq(USER), any()))
                .thenReturn("{\"status\":\"ok\"}");

        ResponseEntity<?> response =
                controller.createPlan(new ProjectPlanRequest("Build a portfolio site"), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", response.getBody());
    }

    @Test
    void planReturns429WhenUserRateLimited() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.createPlan(eq(USER), any()))
                .thenThrow(new RateLimitExceededException("limit reached"));

        ResponseEntity<?> response =
                controller.createPlan(new ProjectPlanRequest("Build a portfolio site"), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }

    @Test
    void planReturns429WhenEngineBusy() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.createPlan(eq(USER), any()))
                .thenThrow(new AiEngineBusyException());

        ResponseEntity<?> response =
                controller.createPlan(new ProjectPlanRequest("Build a portfolio site"), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }

    @Test
    void planReturns502WhenEngineFails() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.createPlan(eq(USER), any()))
                .thenThrow(new AiEngineException("boom"));

        ResponseEntity<?> response =
                controller.createPlan(new ProjectPlanRequest("Build a portfolio site"), authentication);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    @Test
    void generateFileRejectsMissingFilePathWith400() {
        ResponseEntity<?> response =
                controller.generateFile(fileRequest("Build a site", " "), authentication);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void generateFileStreamsBodyOnSuccess() {
        when(authentication.getName()).thenReturn(USER);
        StreamingResponseBody stream = outputStream -> { };
        when(projectBuilderService.streamGeneratedFile(eq(USER), any())).thenReturn(stream);

        ResponseEntity<?> response =
                controller.generateFile(fileRequest("Build a site", "index.html"), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stream, response.getBody());
    }

    @Test
    void generateFileReturns429WhenUserRateLimited() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.streamGeneratedFile(eq(USER), any()))
                .thenThrow(new RateLimitExceededException("limit reached"));

        ResponseEntity<?> response =
                controller.generateFile(fileRequest("Build a site", "index.html"), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }

    @Test
    void editRejectsBlankPromptWith400() {
        ResponseEntity<?> response =
                controller.applyEdit(new ProjectEditRequest(" ", Map.of()), authentication);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void editReturnsEngineJsonOnSuccess() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.applyEdit(eq(USER), any()))
                .thenReturn("{\"status\":\"ok\"}");

        ResponseEntity<?> response =
                controller.applyEdit(new ProjectEditRequest("change theme", Map.of()), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", response.getBody());
    }

    @Test
    void editReturns429WhenEngineBusy() {
        when(authentication.getName()).thenReturn(USER);
        when(projectBuilderService.applyEdit(eq(USER), any()))
                .thenThrow(new AiEngineBusyException());

        ResponseEntity<?> response =
                controller.applyEdit(new ProjectEditRequest("change theme", Map.of()), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }
}
