package com.devassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.devassist.backend.config.SecurityConfig;
import com.devassist.backend.repository.GlobalChatThreadRepository;
import com.devassist.backend.repository.GlobalMessageRepository;
import com.devassist.backend.security.JwtUtil;

import io.jsonwebtoken.Claims;

/**
 * Token accounting behavior of the global chat AI proxy: /ask requires a JWT
 * and forwards the caller's email to the AI engine, engine quota 429s pass
 * through, and the history route the AI engine itself calls stays public.
 */
@WebMvcTest(GlobalChatController.class)
@Import(SecurityConfig.class)
class GlobalChatControllerTokenTest {

    private static final String ASK_BODY = """
            {"question":"hi","session_id":"7"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GlobalChatThreadRepository chatRepository;

    @MockBean
    private GlobalMessageRepository globalMessageRepository;

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
    void askWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/global-chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASK_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void askForwardsAuthenticatedEmailToAiEngine() throws Exception {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(Map.of("answer", "hello"));

        mockMvc.perform(post("/api/global-chat/ask")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("hello"));

        ArgumentCaptor<Object> entityCaptor = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(anyString(), entityCaptor.capture(), eq(Map.class));
        HttpEntity<Map<String, Object>> entity =
                (HttpEntity<Map<String, Object>>) entityCaptor.getValue();
        assertThat(entity.getBody()).containsEntry("user_email", "user@example.com");
    }

    @Test
    void askPassesEngineQuota429Through() throws Exception {
        String quotaJson = "{\"status\":\"quota\",\"scope\":\"user\",\"message\":\"budget spent\"}";
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY,
                        quotaJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/global-chat/ask")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASK_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("quota"))
                .andExpect(jsonPath("$.message").value("budget spent"));
    }

    @Test
    void askConvertsBareEdge429ToBusyNotQuota() throws Exception {
        // A cold-start/burst 429 injected by Cloudflare/Render has an empty (or
        // HTML) body — it must NOT be surfaced to the user as a token limit.
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/global-chat/ask")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ASK_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("busy"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void historyRouteStaysPublicForTheAiEngine() throws Exception {
        when(globalMessageRepository.findByChatIdOrderByTimestampAsc(anyLong()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/global-chat/7"))
                .andExpect(status().isOk());
    }
}
