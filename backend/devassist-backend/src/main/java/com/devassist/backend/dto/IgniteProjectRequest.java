package com.devassist.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Create/update payload for a saved Ignite project. plan and files arrive as
 * JSON values from the frontend and are stored serialized; null fields on
 * update mean "leave unchanged".
 */
public record IgniteProjectRequest(
        String name,
        String slug,
        String summary,
        String prompt,
        JsonNode plan,
        JsonNode files,
        String status) {
}
