package com.devassist.backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devassist.backend.dto.IgniteProjectRequest;
import com.devassist.backend.entity.IgniteProject;
import com.devassist.backend.repository.IgniteProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence for Ignite projects. Every operation is scoped to the owner's
 * email (taken from the JWT by the controller) — one user can never read or
 * modify another user's projects.
 */
@Service
public class IgniteProjectService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STATUS_GENERATING = "generating";
    private static final String STATUS_COMPLETE = "complete";

    private final IgniteProjectRepository repository;

    public IgniteProjectService(IgniteProjectRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Map<String, Object> create(String userEmail, IgniteProjectRequest request) {
        var project = new IgniteProject();
        project.setUserEmail(userEmail);
        project.setName(blankTo(request.name(), "Untitled project"));
        project.setSlug(request.slug());
        project.setSummary(request.summary());
        project.setPrompt(request.prompt());
        project.setPlan(toJson(request.plan(), null));
        project.setFiles(toJson(request.files(), "{}"));
        project.setStatus(normalizeStatus(request.status()));
        return toFullMap(repository.save(project));
    }

    @Transactional
    public Map<String, Object> update(String userEmail, Long id, IgniteProjectRequest request) {
        var project = owned(userEmail, id);
        if (request.name() != null && !request.name().isBlank()) project.setName(request.name());
        if (request.slug() != null) project.setSlug(request.slug());
        if (request.summary() != null) project.setSummary(request.summary());
        if (request.prompt() != null) project.setPrompt(request.prompt());
        if (request.plan() != null) project.setPlan(toJson(request.plan(), null));
        if (request.files() != null) project.setFiles(toJson(request.files(), "{}"));
        if (request.status() != null) project.setStatus(normalizeStatus(request.status()));
        return toFullMap(repository.save(project));
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

    private IgniteProject owned(String userEmail, Long id) {
        return repository.findByIdAndUserEmail(id, userEmail)
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private static String normalizeStatus(String status) {
        return STATUS_COMPLETE.equalsIgnoreCase(status) ? STATUS_COMPLETE : STATUS_GENERATING;
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

    private Map<String, Object> toSummaryMap(IgniteProject p) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("slug", p.getSlug());
        map.put("summary", p.getSummary());
        map.put("status", p.getStatus());
        map.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        map.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        return map;
    }

    private Map<String, Object> toFullMap(IgniteProject p) {
        var map = toSummaryMap(p);
        map.put("prompt", p.getPrompt());
        map.put("plan", parseJson(p.getPlan()));
        map.put("files", parseJson(p.getFiles()));
        return map;
    }
}
