package com.devassist.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import com.devassist.backend.dto.ProjectEditRequest;
import com.devassist.backend.dto.ProjectFileRequest;
import com.devassist.backend.dto.ProjectPlanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proxies Ignite project-builder requests to the AI engine, enforcing per-user
 * rate limits so the limited NVIDIA NIM free credits cannot be drained by one
 * user. File content is streamed through unbuffered for the live-typing UI.
 */
@Service
public class ProjectBuilderService {

    private static final String STREAM_BUSY_MESSAGE =
            "ERROR: The project builder is busy right now. Please try again in a minute.";
    private static final String STREAM_FAILED_MESSAGE =
            "ERROR: File generation failed. Please retry this file.";
    private static final String STREAM_UNREACHABLE_MESSAGE =
            "ERROR: The AI engine is unreachable. Please retry this file.";

    private final RestTemplate restTemplate;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final String aiEngineUrl;

    public ProjectBuilderService(RestTemplate restTemplate,
                                 RateLimitService rateLimitService,
                                 ObjectMapper objectMapper,
                                 @Value("${ai.engine.url:http://127.0.0.1:8000}") String aiEngineUrl) {
        this.restTemplate = restTemplate;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.aiEngineUrl = aiEngineUrl;
    }

    /** Forwards the plan request and returns the AI engine's JSON verbatim. */
    public String createPlan(String userEmail, ProjectPlanRequest request) {
        if (!rateLimitService.tryConsumePlan(userEmail)) {
            throw new RateLimitExceededException(
                    "You have reached the hourly limit for new projects. Please try again later.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("prompt", request.prompt(), "user_email", userEmail), headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    aiEngineUrl + "/project-builder/plan", entity, String.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                throw new AiEngineBusyException(engine429Message(e));
            }
            throw new AiEngineException("AI engine error " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AiEngineException("AI engine is unreachable: " + e.getMessage());
        }
    }

    /**
     * Streams one generated file from the AI engine to the client. Errors that
     * occur after headers are committed are reported in-band with the same
     * "ERROR:" text convention the frontend already uses for /rag-ask streams.
     */
    public StreamingResponseBody streamGeneratedFile(String userEmail, ProjectFileRequest request) {
        if (!rateLimitService.tryConsumeFile(userEmail)) {
            throw new RateLimitExceededException(
                    "You have reached the hourly file-generation limit. Please try again later.");
        }
        Map<String, Object> payload = toEnginePayload(userEmail, request);
        String url = aiEngineUrl + "/project-builder/generate-file";
        return outputStream -> {
            try {
                restTemplate.execute(url, HttpMethod.POST,
                        clientRequest -> {
                            clientRequest.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            objectMapper.writeValue(clientRequest.getBody(), payload);
                        },
                        clientResponse -> {
                            copy(clientResponse.getBody(), outputStream);
                            return null;
                        });
            } catch (HttpStatusCodeException e) {
                writeError(outputStream, streamErrorMessage(e));
            } catch (RestClientException e) {
                writeError(outputStream, STREAM_UNREACHABLE_MESSAGE);
            }
        };
    }

    /** Today's Ignite token usage for one user, as the engine's JSON verbatim. */
    public String getUsage(String userEmail) {
        // Strict template expansion percent-encodes ALL reserved characters:
        // a "+" in an email would otherwise reach FastAPI unencoded and be
        // query-decoded into a space.
        URI uri = UriComponentsBuilder.fromUriString(aiEngineUrl + "/project-builder/usage")
                .queryParam("user_email", "{email}")
                .encode().buildAndExpand(userEmail).toUri();
        try {
            return restTemplate.getForEntity(uri, String.class).getBody();
        } catch (HttpStatusCodeException e) {
            throw new AiEngineException("AI engine error " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AiEngineException("AI engine is unreachable: " + e.getMessage());
        }
    }

    /** Forwards a follow-up edit request; counts against the file quota. */
    public String applyEdit(String userEmail, ProjectEditRequest request) {
        if (!rateLimitService.tryConsumeFile(userEmail)) {
            throw new RateLimitExceededException(
                    "You have reached the hourly file-generation limit. Please try again later.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = Map.of(
                "prompt", request.prompt(),
                "files", request.files() == null ? Map.of() : request.files(),
                "user_email", userEmail);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    aiEngineUrl + "/project-builder/edit",
                    new HttpEntity<>(payload, headers), String.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                throw new AiEngineBusyException(engine429Message(e));
            }
            throw new AiEngineException("AI engine error " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AiEngineException("AI engine is unreachable: " + e.getMessage());
        }
    }

    private static Map<String, Object> toEnginePayload(String userEmail, ProjectFileRequest request) {
        // The AI engine's pydantic models use snake_case field names.
        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", request.prompt());
        payload.put("plan_summary", request.planSummary() == null ? "" : request.planSummary());
        payload.put("file_path", request.filePath());
        payload.put("file_description",
                request.fileDescription() == null ? "" : request.fileDescription());
        payload.put("files_list", request.filesList() == null ? List.of() : request.filesList());
        payload.put("existing_files",
                request.existingFiles() == null ? Map.of() : request.existingFiles());
        payload.put("user_email", userEmail);
        return payload;
    }

    /**
     * The engine's 429 body distinguishes provider rate limits ("busy") from
     * exhausted daily token budgets ("quota", with a scope of user or global).
     * Its message is shown to the user as-is; quota messages tell them which
     * budget ran out, so they are passed through instead of the generic text.
     */
    private Map<String, Object> parse429Body(HttpStatusCodeException e) {
        try {
            return objectMapper.readValue(e.getResponseBodyAsString(),
                    objectMapper.getTypeFactory().constructMapType(
                            Map.class, String.class, Object.class));
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String engine429Message(HttpStatusCodeException e) {
        Object message = parse429Body(e).get("message");
        return message instanceof String s && !s.isBlank()
                ? s
                : "The project builder is busy right now. Please try again in a minute.";
    }

    /**
     * In-band stream errors: quota exhaustion is tagged "[quota:scope]" so the
     * frontend can stop retrying (the budget will not free up within minutes).
     */
    private String streamErrorMessage(HttpStatusCodeException e) {
        if (e.getStatusCode().value() != 429) {
            return STREAM_FAILED_MESSAGE;
        }
        Map<String, Object> body = parse429Body(e);
        if ("quota".equals(body.get("status"))) {
            Object scope = body.get("scope");
            Object message = body.get("message");
            return "ERROR: [quota:" + (scope == null ? "user" : scope) + "] "
                    + (message == null ? "Daily token budget reached." : message);
        }
        return STREAM_BUSY_MESSAGE;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null) {
            return;
        }
        byte[] buffer = new byte[2048];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush(); // push each chunk to the browser immediately for live typing
        }
    }

    private static void writeError(OutputStream out, String message) {
        try {
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // client already disconnected — nobody left to report to
        }
    }
}
