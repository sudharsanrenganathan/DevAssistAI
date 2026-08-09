package com.devassist.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.devassist.backend.dto.ProjectEditRequest;
import com.devassist.backend.dto.ProjectFileRequest;
import com.devassist.backend.dto.ProjectPlanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProjectBuilderServiceTest {

    private static final String USER = "user@example.com";
    private static final String ENGINE_URL = "http://ai-engine:8000";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RateLimitService rateLimitService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectBuilderService service;

    @BeforeEach
    void setUp() {
        service = new ProjectBuilderService(restTemplate, rateLimitService, objectMapper, ENGINE_URL);
    }

    private static ProjectFileRequest fileRequest() {
        return new ProjectFileRequest(
                "Build a portfolio site", "summary", "index.html", "main page",
                List.of("index.html"), Map.of());
    }

    // ---------------- createPlan ----------------

    @Test
    void createPlanReturnsEngineJson() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(true);
        when(restTemplate.postForEntity(eq(ENGINE_URL + "/project-builder/plan"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        String result = service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site"));

        assertEquals("{\"status\":\"ok\"}", result);
    }

    @Test
    void createPlanThrowsWhenUserIsRateLimited() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site")));
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void createPlanMapsEngine429ToBusyException() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));

        assertThrows(AiEngineBusyException.class,
                () -> service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site")));
    }

    @Test
    void createPlanMapsConnectionFailureToEngineException() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThrows(AiEngineException.class,
                () -> service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site")));
    }

    // ---------------- streamGeneratedFile ----------------

    @Test
    void streamThrowsBeforeStartingWhenUserIsRateLimited() {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> service.streamGeneratedFile(USER, fileRequest()));
    }

    @Test
    void streamCopiesEngineBytesAndSendsSnakeCasePayload() throws Exception {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        MockClientHttpRequest outgoing = new MockClientHttpRequest();

        when(restTemplate.execute(eq(ENGINE_URL + "/project-builder/generate-file"),
                eq(HttpMethod.POST), any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    RequestCallback callback = invocation.getArgument(2);
                    callback.doWithRequest(outgoing);
                    ResponseExtractor<?> extractor = invocation.getArgument(3);
                    MockClientHttpResponse engineResponse = new MockClientHttpResponse(
                            "<!DOCTYPE html>".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    return extractor.extractData(engineResponse);
                });

        StreamingResponseBody body = service.streamGeneratedFile(USER, fileRequest());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        body.writeTo(received);

        assertEquals("<!DOCTYPE html>", received.toString(StandardCharsets.UTF_8));
        String sentPayload = outgoing.getBodyAsString();
        assertTrue(sentPayload.contains("\"file_path\":\"index.html\""));
        assertTrue(sentPayload.contains("\"plan_summary\":\"summary\""));
        assertTrue(sentPayload.contains("\"user_email\":\"" + USER + "\""));
    }

    @Test
    void streamReportsBusyInBandWhenEngineReturns429() throws Exception {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.execute(anyString(), eq(HttpMethod.POST),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));

        StreamingResponseBody body = service.streamGeneratedFile(USER, fileRequest());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        body.writeTo(received);

        String text = received.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("ERROR:"));
        assertTrue(text.contains("busy"));
    }

    // ---------------- applyEdit ----------------

    @Test
    void applyEditReturnsEngineJson() {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.postForEntity(eq(ENGINE_URL + "/project-builder/edit"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        String result = service.applyEdit(USER,
                new ProjectEditRequest("make the hero bigger", Map.of("index.html", "<html></html>")));

        assertEquals("{\"status\":\"ok\"}", result);
    }

    @Test
    void applyEditThrowsWhenUserIsRateLimited() {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> service.applyEdit(USER, new ProjectEditRequest("change theme", Map.of())));
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void applyEditMapsEngine429ToBusyException() {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));

        assertThrows(AiEngineBusyException.class,
                () -> service.applyEdit(USER, new ProjectEditRequest("change theme", Map.of())));
    }

    @Test
    void streamReportsUnreachableEngineInBand() throws Exception {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.execute(anyString(), eq(HttpMethod.POST),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        StreamingResponseBody body = service.streamGeneratedFile(USER, fileRequest());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        body.writeTo(received);

        assertTrue(received.toString(StandardCharsets.UTF_8).startsWith("ERROR:"));
    }

    // ---------------- token budgets (engine "quota" 429s) ----------------

    private static HttpClientErrorException engine429(String body) {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void createPlanPassesEngineQuotaMessageThrough() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(engine429("{\"status\":\"quota\",\"scope\":\"user\","
                        + "\"message\":\"You've used your daily Ignite allowance.\"}"));

        AiEngineBusyException e = assertThrows(AiEngineBusyException.class,
                () -> service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site")));
        assertEquals("You've used your daily Ignite allowance.", e.getMessage());
    }

    @Test
    void createPlanSendsUserEmailToEngine() {
        when(rateLimitService.tryConsumePlan(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        service.createPlan(USER, new ProjectPlanRequest("Build a portfolio site"));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertEquals(USER, captor.getValue().getBody().get("user_email"));
    }

    @Test
    void applyEditSendsUserEmailToEngine() {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        service.applyEdit(USER, new ProjectEditRequest("change theme", Map.of()));

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertEquals(USER, captor.getValue().getBody().get("user_email"));
    }

    @Test
    void streamTagsQuotaExhaustionInBandSoTheClientStopsRetrying() throws Exception {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.execute(anyString(), eq(HttpMethod.POST),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(engine429("{\"status\":\"quota\",\"scope\":\"global\","
                        + "\"message\":\"App capacity reached.\"}"));

        StreamingResponseBody body = service.streamGeneratedFile(USER, fileRequest());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        body.writeTo(received);

        assertEquals("ERROR: [quota:global] App capacity reached.",
                received.toString(StandardCharsets.UTF_8));
    }

    @Test
    void streamStillReportsPlainBusyForNonQuota429() throws Exception {
        when(rateLimitService.tryConsumeFile(USER)).thenReturn(true);
        when(restTemplate.execute(anyString(), eq(HttpMethod.POST),
                any(RequestCallback.class), any(ResponseExtractor.class)))
                .thenThrow(engine429("{\"status\":\"busy\",\"message\":\"busy\"}"));

        StreamingResponseBody body = service.streamGeneratedFile(USER, fileRequest());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        body.writeTo(received);

        String text = received.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("ERROR:"));
        assertTrue(text.contains("busy"));
    }

    // ---------------- getUsage ----------------

    @Test
    void getUsageReturnsEngineJsonWithEncodedEmail() {
        String usageJson = "{\"user_tokens\":1200,\"user_limit\":20000}";
        when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(usageJson));

        String result = service.getUsage(USER);

        assertEquals(usageJson, result);
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForEntity(captor.capture(), eq(String.class));
        assertEquals(ENGINE_URL + "/project-builder/usage?user_email=user%40example.com",
                captor.getValue().toString());
    }

    @Test
    void getUsageMapsConnectionFailureToEngineException() {
        when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThrows(AiEngineException.class, () -> service.getUsage(USER));
    }
}
