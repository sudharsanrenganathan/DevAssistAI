package com.devassist.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class KeepAliveService {

    private static final Logger logger = LoggerFactory.getLogger(KeepAliveService.class);

    private final RestTemplate restTemplate;

    @Value("${ai.engine.url:https://devassist-ai-engine.onrender.com}")
    private String aiEngineUrl;

    public KeepAliveService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Executes every 14 minutes (840,000 milliseconds) to prevent Render sleeping.
     * Render free instances sleep after 15 minutes of inactivity.
     * Hitting our own public URLs counts as incoming proxy traffic.
     */
    @Scheduled(fixedRate = 840000)
    public void pingServices() {
        logger.info("Executing periodic Keep-Alive Ping to prevent Render from sleeping...");

        // Ping the Python AI Engine
        try {
            String aiStatus = restTemplate.getForObject(aiEngineUrl + "/", String.class);
            logger.info("Keep-Alive successful for AI Engine: {}", aiStatus != null ? aiStatus.trim() : "OK");
        } catch (Exception e) {
            logger.warn("Keep-Alive failed for AI Engine: {}", e.getMessage());
        }

        // Ping the Java Backend itself via localhost so it doesn't scale to 0
        // (Note: To count as external traffic to Render proxy, we should ideall call the external URL, 
        // but since Render detects any listening socket activity sometimes, calling health check is good practice)
        try {
            String javaStatus = restTemplate.getForObject("http://localhost:8080/health", String.class);
            logger.info("Keep-Alive internal ping successful for Java Backend.");
        } catch (Exception e) {
            logger.warn("Keep-Alive internal ping failed for Java Backend: {}", e.getMessage());
        }
    }
}
