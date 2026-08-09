package com.devassist.backend.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.devassist.backend.dto.CircuitAskRequest;

/**
 * Proxies Circuit asks to the AI engine, enforcing a per-user hourly rate
 * limit. The engine's 429 bodies (daily token quota or provider busy) are
 * passed through verbatim via {@link AiEngineRateLimitedException}.
 */
@Service
public class CircuitService {

    private final RestTemplate restTemplate;
    private final RateLimitService rateLimitService;
    private final String aiEngineUrl;

    public CircuitService(RestTemplate restTemplate,
                          RateLimitService rateLimitService,
                          @Value("${ai.engine.url:http://127.0.0.1:8000}") String aiEngineUrl) {
        this.restTemplate = restTemplate;
        this.rateLimitService = rateLimitService;
        this.aiEngineUrl = aiEngineUrl;
    }

    /** Forwards the ask and returns the engine's circuit-spec JSON verbatim. */
    public String ask(String userEmail, CircuitAskRequest request) {
        if (!rateLimitService.tryConsumeCircuit(userEmail)) {
            throw new RateLimitExceededException(
                    "You have reached the hourly limit for circuits. Please try again later.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("prompt", request.prompt(),
                       "context", request.context() == null ? "" : request.context(),
                       "user_email", userEmail), headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    aiEngineUrl + "/circuit/ask", entity, String.class);
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                throw new AiEngineRateLimitedException(e.getResponseBodyAsString());
            }
            throw new AiEngineException("AI engine error " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new AiEngineException("AI engine is unreachable: " + e.getMessage());
        }
    }
}
