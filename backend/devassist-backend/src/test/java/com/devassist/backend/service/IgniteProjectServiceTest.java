package com.devassist.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.devassist.backend.dto.IgniteProjectRequest;
import com.devassist.backend.entity.IgniteProject;
import com.devassist.backend.repository.IgniteProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IgniteProjectServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "user@example.com";

    @Mock
    private IgniteProjectRepository repository;

    private IgniteProjectService service;

    @BeforeEach
    void setUp() {
        service = new IgniteProjectService(repository);
    }

    private static IgniteProject saved(Long id, String owner) {
        var p = new IgniteProject();
        p.setId(id);
        p.setUserEmail(owner);
        p.setName("Portfolio");
        p.setFiles("{\"index.html\":\"<html></html>\"}");
        p.setStatus("generating");
        return p;
    }

    @Test
    void createStoresOwnerSerializedJsonAndDefaults() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var request = new IgniteProjectRequest(
                "My Site", "my-site", "A site", "build me a site",
                JSON.readTree("{\"files\":[{\"path\":\"index.html\"}]}"),
                JSON.readTree("{\"index.html\":\"<html></html>\"}"),
                null);

        Map<String, Object> result = service.create(OWNER, request);

        var captor = org.mockito.ArgumentCaptor.forClass(IgniteProject.class);
        verify(repository).save(captor.capture());
        IgniteProject entity = captor.getValue();
        assertThat(entity.getUserEmail()).isEqualTo(OWNER);
        assertThat(entity.getPlan()).contains("index.html");
        assertThat(entity.getFiles()).contains("<html></html>");
        assertThat(entity.getStatus()).isEqualTo("generating"); // default when absent
        assertThat(result.get("name")).isEqualTo("My Site");
    }

    @Test
    void createFallsBackToUntitledWhenNameBlank() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var request = new IgniteProjectRequest("  ", null, null, null, null, null, null);
        Map<String, Object> result = service.create(OWNER, request);
        assertThat(result.get("name")).isEqualTo("Untitled project");
    }

    @Test
    void updateAppliesOnlyProvidedFields() throws Exception {
        var existing = saved(7L, OWNER);
        when(repository.findByIdAndUserEmail(7L, OWNER)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new IgniteProjectRequest(null, null, null, null, null,
                JSON.readTree("{\"index.html\":\"<html>v2</html>\"}"), "complete");
        Map<String, Object> result = service.update(OWNER, 7L, request);

        assertThat(existing.getName()).isEqualTo("Portfolio"); // untouched
        assertThat(existing.getFiles()).contains("v2");
        assertThat(existing.getStatus()).isEqualTo("complete");
        assertThat(result.get("status")).isEqualTo("complete");
    }

    @Test
    void otherUsersProjectIsInvisible() {
        when(repository.findByIdAndUserEmail(9L, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(OWNER, 9L))
                .isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 9L))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void listReturnsSummariesWithoutFilesPayload() {
        when(repository.findByUserEmailOrderByUpdatedAtDesc(OWNER))
                .thenReturn(java.util.List.of(saved(1L, OWNER)));
        var list = service.list(OWNER);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsKeys("id", "name", "status", "updatedAt");
        assertThat(list.get(0)).doesNotContainKeys("files", "plan", "prompt");
    }

    @Test
    void getParsesStoredJsonBackToObjects() {
        when(repository.findByIdAndUserEmail(1L, OWNER)).thenReturn(Optional.of(saved(1L, OWNER)));
        Map<String, Object> result = service.get(OWNER, 1L);
        assertThat(result.get("files")).isInstanceOf(com.fasterxml.jackson.databind.JsonNode.class);
    }
}
