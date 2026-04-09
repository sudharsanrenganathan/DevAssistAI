package com.devassist.backend.controller;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
@RestController
@RequestMapping("/ai")
public class CodeAnalyzerController {

    @org.springframework.beans.factory.annotation.Value("${ai.engine.url:http://127.0.0.1:8000}")
    private String aiEngineUrl;

    @PostMapping("/analyze")
public Map<String, Object> analyze(@RequestBody Map<String, String> req) {

    RestTemplate restTemplate = new RestTemplate();

    String url = aiEngineUrl + "/code-analyze";

    Map<String, String> request = new HashMap<>();
    request.put("code", req.get("code"));
    request.put("language", req.get("language"));

    return restTemplate.postForObject(url, request, Map.class);
}
}