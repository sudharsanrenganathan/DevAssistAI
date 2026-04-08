package com.devassist.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.util.HashMap;
import java.util.Map;

@Service
public class AiIntegrationService {

    private final String AI_ENGINE_URL = "http://127.0.0.1:8000";

    public String askGlobalAI(String question) {

        RestTemplate restTemplate = new RestTemplate();

        String url = AI_ENGINE_URL + "/global-ai";

        Map<String, String> request = new HashMap<>();
        request.put("question", question);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(url, entity, String.class);

        return response.getBody();
    }

    public String askSecretAI(String question) {

        RestTemplate restTemplate = new RestTemplate();

        String url = AI_ENGINE_URL + "/secret-intelligence";

        Map<String, String> request = new HashMap<>();
        request.put("question", question);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response =
                restTemplate.postForEntity(url, entity, String.class);

        return response.getBody();
    }
}