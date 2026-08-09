package com.devassist.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.backend.config.SecurityConfig;
import com.devassist.backend.security.JwtUtil;
import com.devassist.backend.service.IgniteProjectService;
import com.devassist.backend.service.ProjectNotFoundException;

import io.jsonwebtoken.Claims;

/**
 * Saved-projects API: requires a JWT, the owner always comes from the token,
 * and another user's project id answers 404.
 */
@WebMvcTest(IgniteProjectController.class)
@Import(SecurityConfig.class)
class IgniteProjectControllerTest {

    private static final String BODY = """
            {"name":"My Site","status":"generating","files":{"index.html":"<html></html>"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IgniteProjectService projectService;

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
    void createWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/project-builder/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUsesAuthenticatedEmailAsOwner() throws Exception {
        when(projectService.create(eq("user@example.com"), any()))
                .thenReturn(Map.of("id", 1L, "name", "My Site"));

        mockMvc.perform(post("/api/project-builder/projects")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("My Site"));
    }

    @Test
    void someoneElsesProjectAnswers404() throws Exception {
        when(projectService.get(eq("user@example.com"), eq(42L)))
                .thenThrow(new ProjectNotFoundException(42L));

        mockMvc.perform(get("/api/project-builder/projects/42")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project not found."));
    }

    @Test
    void listReturnsCurrentUsersProjects() throws Exception {
        when(projectService.list("user@example.com"))
                .thenReturn(java.util.List.of(Map.of("id", 1L, "name", "My Site")));

        mockMvc.perform(get("/api/project-builder/projects")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("My Site"));
    }
}
