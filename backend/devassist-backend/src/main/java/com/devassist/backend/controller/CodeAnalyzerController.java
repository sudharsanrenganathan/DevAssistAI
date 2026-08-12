package com.devassist.backend.controller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
@RestController
@RequestMapping("/ai")
public class CodeAnalyzerController {

    @org.springframework.beans.factory.annotation.Value("${ai.engine.url:http://127.0.0.1:8000}")
    private String aiEngineUrl;

    private final RestTemplate restTemplate;

    public CodeAnalyzerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> req,
                                     Authentication authentication) {

        String url = aiEngineUrl + "/code-analyze";

        Map<String, String> request = new HashMap<>();
        request.put("code", req.get("code"));
        request.put("language", req.get("language"));
        request.put("user_email", authentication.getName());

        try {
            return ResponseEntity.ok(restTemplate.postForObject(url, request, Map.class));
        } catch (HttpClientErrorException.TooManyRequests e) {
            return throttleResponse(e.getResponseBodyAsString());
        }
    }

    /**
     * Shape a 429 for the client. A genuine daily-token-quota 429 from the AI
     * engine is JSON carrying {@code status:"quota"} — pass it through untouched
     * so the user sees the real allowance message. Anything else is a BARE 429
     * injected by Cloudflare/Render during a cold start or request burst; return
     * clean {@code status:"busy"} JSON so the client shows a transient
     * "waking up" notice instead of a false "token limit reached".
     */
    private ResponseEntity<?> throttleResponse(String engineBody) {
        if (engineBody != null && engineBody.contains("\"quota\"")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(engineBody);
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "status", "busy",
                "message", "The AI engine is waking up. Please try again in a moment."));
    }
}
