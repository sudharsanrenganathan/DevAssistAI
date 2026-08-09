package com.devassist.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import com.devassist.backend.dto.CircuitAskRequest;
import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.AiEngineRateLimitedException;
import com.devassist.backend.service.CircuitService;
import com.devassist.backend.service.RateLimitExceededException;

@ExtendWith(MockitoExtension.class)
class CircuitControllerTest {

    private static final String USER = "user@example.com";

    @Mock
    private CircuitService circuitService;

    private CircuitController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new CircuitController(circuitService);
        authentication = mock(Authentication.class);
    }

    @Test
    void askRejectsBlankPromptWith400() {
        ResponseEntity<?> response = controller.ask(new CircuitAskRequest("  ", null), authentication);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void askReturnsEngineJsonOnSuccess() {
        when(authentication.getName()).thenReturn(USER);
        when(circuitService.ask(eq(USER), any()))
                .thenReturn("{\"status\":\"ok\",\"circuit\":{}}");

        ResponseEntity<?> response =
                controller.ask(new CircuitAskRequest("binary search", null), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"status\":\"ok\",\"circuit\":{}}", response.getBody());
    }

    @Test
    void askReturns429WhenUserRateLimited() {
        when(authentication.getName()).thenReturn(USER);
        when(circuitService.ask(eq(USER), any()))
                .thenThrow(new RateLimitExceededException("limit reached"));

        ResponseEntity<?> response =
                controller.ask(new CircuitAskRequest("binary search", null), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }

    @Test
    void askPassesThroughEngine429BodyVerbatim() {
        when(authentication.getName()).thenReturn(USER);
        String quotaBody = "{\"status\":\"quota\",\"scope\":\"user\",\"message\":\"budget spent\"}";
        when(circuitService.ask(eq(USER), any()))
                .thenThrow(new AiEngineRateLimitedException(quotaBody));

        ResponseEntity<?> response =
                controller.ask(new CircuitAskRequest("binary search", null), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals(quotaBody, response.getBody());
    }

    @Test
    void askFallsBackToBusyJsonWhenEngine429BodyIsBlank() {
        when(authentication.getName()).thenReturn(USER);
        when(circuitService.ask(eq(USER), any()))
                .thenThrow(new AiEngineRateLimitedException(""));

        ResponseEntity<?> response =
                controller.ask(new CircuitAskRequest("binary search", null), authentication);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals(true, String.valueOf(response.getBody()).contains("\"busy\""));
    }

    @Test
    void askReturns502WhenEngineFails() {
        when(authentication.getName()).thenReturn(USER);
        when(circuitService.ask(eq(USER), any()))
                .thenThrow(new AiEngineException("boom"));

        ResponseEntity<?> response =
                controller.ask(new CircuitAskRequest("binary search", null), authentication);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
