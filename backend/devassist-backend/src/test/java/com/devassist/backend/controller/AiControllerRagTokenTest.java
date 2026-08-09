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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import com.devassist.backend.config.SecurityConfig;
import com.devassist.backend.repository.ChatRepository;
import com.devassist.backend.repository.ChatSessionRepository;
import com.devassist.backend.repository.DocumentRepository;
import com.devassist.backend.security.JwtUtil;

import io.jsonwebtoken.Claims;

/**
 * /ai/rag charges the caller's daily token budget: it requires a JWT,
 * forwards the authenticated email to the AI engine, and maps the engine's
 * in-band "ERROR: [quota:scope] ..." stream text to a 429 response.
 */
@WebMvcTest(controllers = AiController.class,
        properties = "supabase.url=http://supabase.test")
@Import(SecurityConfig.class)
class AiControllerRagTokenTest {

    private static final String RAG_BODY = """
            {"question":"hi","file_path":"https://storage.test/doc.pdf","session_id":"7"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentRepository documentRepository;

    @MockBean
    private ChatRepository chatRepository;

    @MockBean
    private ChatSessionRepository sessionRepository;

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
    void ragWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/ai/rag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAG_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ragForwardsAuthenticatedEmailToAiEngine() throws Exception {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("the answer"));

        mockMvc.perform(post("/ai/rag")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAG_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("the answer"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(String.class));
        assertThat(entityCaptor.getValue().getBody())
                .containsEntry("user_email", "user@example.com");
    }

    @Test
    void ragMapsInbandQuotaErrorTo429() throws Exception {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ERROR: [quota:user] budget spent"));

        mockMvc.perform(post("/ai/rag")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RAG_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("budget spent"));
    }
}
