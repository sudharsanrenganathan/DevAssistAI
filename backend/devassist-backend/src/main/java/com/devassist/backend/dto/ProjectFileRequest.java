package com.devassist.backend.dto;

import java.util.List;
import java.util.Map;

/** Request body for POST /api/project-builder/generate-file. */
public record ProjectFileRequest(
        String prompt,
        String planSummary,
        String filePath,
        String fileDescription,
        List<String> filesList,
        Map<String, String> existingFiles) {
}
