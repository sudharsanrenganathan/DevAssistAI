package com.devassist.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Autowired
    private DataSource dataSource;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "running");
        response.put("message", "DevAssist AI Backend Running Successfully 🚀");
        
        // Test database connectivity
        try (Connection conn = dataSource.getConnection()) {
            response.put("database", "connected");
            response.put("dbUrl", conn.getMetaData().getURL());
        } catch (Exception e) {
            response.put("database", "disconnected");
            response.put("dbError", e.getMessage());
        }
        
        return response;
    }

}