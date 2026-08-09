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
            // Engine 429 (daily token quota) — pass the JSON body through so
            // the frontend can show the engine's message.
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        }
    }
}
