package com.devassist.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.devassist.backend.service.AiEngineException;
import com.devassist.backend.service.ProjectBuilderService;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    private static final String USER = "user@example.com";

    @Mock
    private ProjectBuilderService projectBuilderService;

    private UsageController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new UsageController(projectBuilderService);
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(USER);
    }

    @Test
    void getUsageReturnsEngineJsonOnSuccess() {
        String usageJson = "{\"user_tokens\":1200,\"user_limit\":20000,"
                + "\"global_tokens\":45000,\"global_limit\":180000}";
        when(projectBuilderService.getUsage(USER)).thenReturn(usageJson);

        ResponseEntity<?> response = controller.getUsage(authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(usageJson, response.getBody());
    }

    @Test
    void getUsageReturns502WhenEngineFails() {
        when(projectBuilderService.getUsage(USER))
                .thenThrow(new AiEngineException("engine down"));

        ResponseEntity<?> response = controller.getUsage(authentication);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
