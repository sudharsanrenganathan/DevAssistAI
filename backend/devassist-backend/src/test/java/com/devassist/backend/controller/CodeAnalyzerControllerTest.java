package com.devassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.devassist.backend.config.SecurityConfig;
import com.devassist.backend.security.JwtUtil;

import io.jsonwebtoken.Claims;

/**
 * /ai/analyze charges the caller's daily token budget: it requires a JWT,
 * forwards the authenticated email to the AI engine, and passes engine quota
 * 429s through to the client.
 */
@WebMvcTest(CodeAnalyzerController.class)
@Import(SecurityConfig.class)
class CodeAnalyzerControllerTest {

    private static final String ANALYZE_BODY = """
            {"code":"print(1)","language":"python"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestTemplate restTemplate;

    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void stubValidToken() {
        Claims claims = mock(Claims.class);
        when(claims.get("email", String.class)).thenReturn("user@example.com");
        when(claims.get("role", String.class)).thenReturn("authenticated");
        when(claims.getSubject()).thenReturn("supabase-user-id");
        when(jwtUtil.parseToken(anyString())).thenReturn(claims);
    }

    @Test
    void analyzeWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/ai/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ANALYZE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeForwardsAuthenticatedEmailToAiEngine() throws Exception {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("errors", "No errors found"));

        mockMvc.perform(post("/ai/analyze")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ANALYZE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").value("No errors found"));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(anyString(), payloadCaptor.capture(), eq(Map.class));
        assertThat((Map<String, String>) payloadCaptor.getValue())
                .containsEntry("user_email", "user@example.com");
    }

    @Test
    void analyzePassesEngineQuota429Through() throws Exception {
        String quotaJson = "{\"status\":\"quota\",\"scope\":\"user\",\"message\":\"budget spent\"}";
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY,
                        quotaJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mockMvc.perform(post("/ai/analyze")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ANALYZE_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("quota"))
                .andExpect(jsonPath("$.message").value("budget spent"));
    }
}
