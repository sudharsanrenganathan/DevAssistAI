package com.devassist.backend.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.backend.config.SecurityConfig;
import com.devassist.backend.entity.VmLeaderboard;
import com.devassist.backend.entity.VmSubmission;
import com.devassist.backend.repository.VmLeaderboardRepository;
import com.devassist.backend.repository.VmProblemRepository;
import com.devassist.backend.repository.VmSubmissionRepository;
import com.devassist.backend.security.JwtUtil;

import io.jsonwebtoken.Claims;

/**
 * VoidMain isolation: every /vm endpoint needs a JWT and derives the user from
 * the token (never a client param), so one user only ever sees their own
 * submissions/stats. The leaderboard is viewable by any logged-in user, flags
 * the caller's own row, and never leaks email addresses.
 */
@WebMvcTest(VoidMainController.class)
@Import(SecurityConfig.class)
class VoidMainControllerTest {

    private static final String ME = "user@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VmProblemRepository problemRepo;

    @MockBean
    private VmSubmissionRepository submissionRepo;

    @MockBean
    private VmLeaderboardRepository leaderboardRepo;

    @MockBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void stubValidToken() {
        Claims claims = mock(Claims.class);
        when(claims.get("email", String.class)).thenReturn(ME);
        when(claims.get("role", String.class)).thenReturn("authenticated");
        when(claims.getSubject()).thenReturn("supabase-user-id");
        when(jwtUtil.parseToken(anyString())).thenReturn(claims);
    }

    @Test
    void submissionsWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/vm/submissions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/vm/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"questionId\":\"E001\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void submissionsReturnOnlyTheAuthenticatedUsersHistory() throws Exception {
        VmSubmission sub = new VmSubmission();
        sub.setUsername(ME);
        sub.setProblemId("E001");
        sub.setVerdict("Accepted");
        when(submissionRepo.findByUsernameOrderBySubmittedAtDesc(ME))
                .thenReturn(List.of(sub));

        mockMvc.perform(get("/vm/submissions")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].problemId").value("E001"))
                .andExpect(jsonPath("$[0].verdict").value("Accepted"));

        // Identity must come from the JWT principal, never a client value.
        verify(submissionRepo).findByUsernameOrderBySubmittedAtDesc(ME);
    }

    @Test
    void problemsSolvedSetUsesAuthenticatedUser() throws Exception {
        when(problemRepo.findAll()).thenReturn(new java.util.ArrayList<>()); // controller sorts in place
        when(submissionRepo.findSolvedProblemIds(ME)).thenReturn(List.of());

        mockMvc.perform(get("/vm/problems")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());

        verify(submissionRepo).findSolvedProblemIds(ME);
    }

    @Test
    void statsComputeFromTheAuthenticatedUsersSolves() throws Exception {
        when(submissionRepo.findSolvedProblemIds(ME)).thenReturn(List.of("E001", "M001"));
        when(submissionRepo.findAttemptedProblemIds(ME)).thenReturn(List.of("E001", "M001", "E002"));
        when(submissionRepo.findByUsernameOrderBySubmittedAtDesc(ME)).thenReturn(List.of());

        mockMvc.perform(get("/vm/stats")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solved").value(2))
                .andExpect(jsonPath("$.easySolved").value(1))
                .andExpect(jsonPath("$.mediumSolved").value(1))
                .andExpect(jsonPath("$.score").value(30)); // 1*10 + 1*20
    }

    @Test
    void leaderboardShowsDisplayNamesFlagsYouAndHidesEmails() throws Exception {
        VmLeaderboard mine = new VmLeaderboard();
        mine.setUsername(ME);
        mine.setDisplayName("Alice");
        mine.setSolved(5);
        mine.setAttempted(7);
        mine.setScore(100);

        VmLeaderboard other = new VmLeaderboard();
        other.setUsername("someoneelse@example.com");
        other.setDisplayName("Bob");
        other.setSolved(3);
        other.setAttempted(4);
        other.setScore(60);

        when(leaderboardRepo.findTop50ByOrderByScoreDescSolvedDesc())
                .thenReturn(List.of(mine, other));

        mockMvc.perform(get("/vm/leaderboard")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].you").value(true))
                .andExpect(jsonPath("$[1].displayName").value("Bob"))
                .andExpect(jsonPath("$[1].you").value(false))
                // email (the internal key) must never be serialized publicly
                .andExpect(jsonPath("$[0].username").doesNotExist())
                .andExpect(jsonPath("$[1].username").doesNotExist());
    }

    @Test
    void leaderboardFallsBackToEmailPrefixWhenNoDisplayName() throws Exception {
        VmLeaderboard legacy = new VmLeaderboard();
        legacy.setUsername("charlie@example.com");
        legacy.setDisplayName(null); // old row created before display names existed
        legacy.setSolved(1);
        legacy.setAttempted(1);
        legacy.setScore(10);

        when(leaderboardRepo.findTop50ByOrderByScoreDescSolvedDesc())
                .thenReturn(List.of(legacy));

        mockMvc.perform(get("/vm/leaderboard")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("charlie"))
                .andExpect(jsonPath("$[0].username").doesNotExist());
    }
}
