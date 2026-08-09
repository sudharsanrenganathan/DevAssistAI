package com.devassist.backend.dto;

import java.util.Map;

/** Request body for POST /api/project-builder/edit (follow-up change chat). */
public record ProjectEditRequest(String prompt, Map<String, String> files) {
}
