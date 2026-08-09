package com.devassist.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import com.devassist.backend.dto.CircuitSessionRequest;
import com.devassist.backend.service.CircuitSessionNotFoundException;
import com.devassist.backend.service.CircuitSessionService;

@ExtendWith(MockitoExtension.class)
class CircuitSessionControllerTest {

    private static final String USER = "user@example.com";

    @Mock
    private CircuitSessionService sessionService;

    private CircuitSessionController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new CircuitSessionController(sessionService);
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(USER);
    }

    @Test
    void createReturnsSavedSession() {
        when(sessionService.create(eq(USER), any()))
                .thenReturn(Map.of("id", 1L, "title", "Binary Search"));

        ResponseEntity<?> response =
                controller.create(new CircuitSessionRequest("Binary Search", null, 1), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("id", 1L, "title", "Binary Search"), response.getBody());
    }

    @Test
    void listReturnsUsersSessions() {
        when(sessionService.list(USER)).thenReturn(List.of(Map.of("id", 1L)));

        ResponseEntity<?> response = controller.list(authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(Map.of("id", 1L)), response.getBody());
    }

    @Test
    void getUnknownSessionReturns404() {
        when(sessionService.get(USER, 9L)).thenThrow(new CircuitSessionNotFoundException(9L));

        ResponseEntity<?> response = controller.get(9L, authentication);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void updateUnknownSessionReturns404() {
        when(sessionService.update(eq(USER), eq(9L), any()))
                .thenThrow(new CircuitSessionNotFoundException(9L));

        ResponseEntity<?> response =
                controller.update(9L, new CircuitSessionRequest("t", null, null), authentication);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void deleteReturnsOkOnSuccessAnd404WhenMissing() {
        ResponseEntity<?> ok = controller.delete(2L, authentication);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        doThrow(new CircuitSessionNotFoundException(3L)).when(sessionService).delete(USER, 3L);
        ResponseEntity<?> missing = controller.delete(3L, authentication);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }
}
