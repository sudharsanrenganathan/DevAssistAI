package com.devassist.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.devassist.backend.dto.CircuitAskRequest;

@ExtendWith(MockitoExtension.class)
class CircuitServiceTest {

    private static final String USER = "user@example.com";
    private static final String ENGINE_URL = "http://ai-engine.test";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RateLimitService rateLimitService;

    private CircuitService service;

    @BeforeEach
    void setUp() {
        service = new CircuitService(restTemplate, rateLimitService, ENGINE_URL);
    }

    private static HttpClientErrorException engine429(String body) {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void askForwardsPromptAndUserEmailAndReturnsEngineJson() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(true);
        when(restTemplate.postForEntity(eq(ENGINE_URL + "/circuit/ask"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        String result = service.ask(USER, new CircuitAskRequest("binary search", null));

        assertEquals("{\"status\":\"ok\"}", result);
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(ENGINE_URL + "/circuit/ask"),
                captor.capture(), eq(String.class));
        Map<String, Object> payload = captor.getValue().getBody();
        assertEquals("binary search", payload.get("prompt"));
        assertEquals(USER, payload.get("user_email"));
        assertEquals("", payload.get("context")); // null context becomes empty string
    }

    @Test
    @SuppressWarnings("unchecked")
    void askForwardsConversationContext() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        service.ask(USER, new CircuitAskRequest("now I am in 5th standard",
                "User: become PM\nCircuit: drew PM roadmap"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertEquals("User: become PM\nCircuit: drew PM roadmap",
                captor.getValue().getBody().get("context"));
    }

    @Test
    void askThrowsRateLimitExceededWithoutCallingEngine() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> service.ask(USER, new CircuitAskRequest("binary search", null)));
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void askPassesThroughEngine429BodyVerbatim() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(true);
        String quotaBody = "{\"status\":\"quota\",\"scope\":\"user\",\"message\":\"budget spent\"}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(engine429(quotaBody));

        AiEngineRateLimitedException e = assertThrows(AiEngineRateLimitedException.class,
                () -> service.ask(USER, new CircuitAskRequest("binary search", null)));
        assertEquals(quotaBody, e.getResponseBody());
    }

    @Test
    void askWrapsOtherHttpErrorsAsAiEngineException() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                        "boom", HttpHeaders.EMPTY, new byte[0], null));

        assertThrows(AiEngineException.class,
                () -> service.ask(USER, new CircuitAskRequest("binary search", null)));
    }

    @Test
    void askWrapsConnectionFailureAsAiEngineException() {
        when(rateLimitService.tryConsumeCircuit(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThrows(AiEngineException.class,
                () -> service.ask(USER, new CircuitAskRequest("binary search", null)));
    }
}
