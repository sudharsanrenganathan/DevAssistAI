package com.devassist.backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devassist.backend.dto.CircuitSessionRequest;
import com.devassist.backend.entity.CircuitSession;
import com.devassist.backend.repository.CircuitSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence for Circuit board sessions. Every operation is scoped to the
 * owner's email (taken from the JWT by the controller) — one user can never
 * read or modify another user's circuits.
 */
@Service
public class CircuitSessionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CircuitSessionRepository repository;

    public CircuitSessionService(CircuitSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Map<String, Object> create(String userEmail, CircuitSessionRequest request) {
        var session = new CircuitSession();
        session.setUserEmail(userEmail);
        session.setTitle(blankTo(request.title(), "Untitled circuit"));
        session.setPayload(toJson(request.payload(), "{}"));
        session.setSubCount(request.subCount() == null ? 0 : Math.max(0, request.subCount()));
        return toFullMap(repository.save(session));
    }

    @Transactional
    public Map<String, Object> update(String userEmail, Long id, CircuitSessionRequest request) {
        var session = owned(userEmail, id);
        if (request.title() != null && !request.title().isBlank()) session.setTitle(request.title());
        if (request.payload() != null) session.setPayload(toJson(request.payload(), "{}"));
        if (request.subCount() != null) session.setSubCount(Math.max(0, request.subCount()));
        return toFullMap(repository.save(session));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String userEmail) {
        return repository.findByUserEmailOrderByUpdatedAtDesc(userEmail).stream()
                .map(this::toSummaryMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String userEmail, Long id) {
        return toFullMap(owned(userEmail, id));
    }

    @Transactional
    public void delete(String userEmail, Long id) {
        repository.delete(owned(userEmail, id));
    }

    private CircuitSession owned(String userEmail, Long id) {
        return repository.findByIdAndUserEmail(id, userEmail)
                .orElseThrow(() -> new CircuitSessionNotFoundException(id));
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String toJson(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.toString();
    }

    private static JsonNode parseJson(String json) {
        if (json == null) return null;
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, Object> toSummaryMap(CircuitSession s) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", s.getId());
        map.put("title", s.getTitle());
        map.put("subCount", s.getSubCount());
        map.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        map.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
        return map;
    }

    private Map<String, Object> toFullMap(CircuitSession s) {
        var map = toSummaryMap(s);
        map.put("payload", parseJson(s.getPayload()));
        return map;
    }
}
