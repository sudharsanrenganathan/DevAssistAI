package com.devassist.backend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.devassist.backend.controller.ProjectBuilderController;
import com.devassist.backend.security.JwtUtil;
import com.devassist.backend.service.ProjectBuilderService;

import io.jsonwebtoken.Claims;

/**
 * Drives /api/project-builder/generate-file through the real security filter
 * chain including the ASYNC re-dispatch that StreamingResponseBody triggers.
 * Regression test for the bug where the plan call succeeded but every file
 * generation failed with 403: JwtFilter only authenticates the initial REQUEST
 * dispatch, so the ASYNC dispatch arrived anonymous and was denied.
 */
@WebMvcTest(ProjectBuilderController.class)
@Import(SecurityConfig.class)
class SecurityConfigStreamingTest {

    private static final String FILE_BODY = """
            {"prompt":"Build a site","filePath":"index.html"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProjectBuilderService projectBuilderService;

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
    void generateFileStreamsToCompletionWithValidJwt() throws Exception {
        StreamingResponseBody stream = out ->
                out.write("<!DOCTYPE html>".getBytes(StandardCharsets.UTF_8));
        when(projectBuilderService.streamGeneratedFile(eq("user@example.com"), any()))
                .thenReturn(stream);

        MvcResult started = mockMvc.perform(post("/api/project-builder/generate-file")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FILE_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string("<!DOCTYPE html>"));
    }

    @Test
    void generateFileWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/project-builder/generate-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FILE_BODY))
                .andExpect(status().isForbidden());
    }
}
