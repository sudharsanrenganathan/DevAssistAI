package com.devassist.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.devassist.backend.dto.CircuitSessionRequest;
import com.devassist.backend.entity.CircuitSession;
import com.devassist.backend.repository.CircuitSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CircuitSessionServiceTest {

    private static final String USER = "user@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private CircuitSessionRepository repository;

    private CircuitSessionService service;

    @BeforeEach
    void setUp() {
        service = new CircuitSessionService(repository);
    }

    private void stubSaveEcho() {
        when(repository.save(any(CircuitSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createStoresOwnerTitleAndSerializedPayload() throws Exception {
        stubSaveEcho();
        var payload = JSON.readTree("{\"circuits\":[{\"title\":\"Binary Search\"}]}");
        Map<String, Object> result = service.create(USER,
                new CircuitSessionRequest("Binary Search", payload, 1));

        var captor = org.mockito.ArgumentCaptor.forClass(CircuitSession.class);
        verify(repository).save(captor.capture());
        assertEquals(USER, captor.getValue().getUserEmail());
        assertEquals("Binary Search", captor.getValue().getTitle());
        assertEquals(1, captor.getValue().getSubCount());
        assertEquals("Binary Search", result.get("title"));
        assertEquals(payload, result.get("payload"));
    }

    @Test
    void createFallsBackToDefaultTitleAndEmptyPayload() {
        stubSaveEcho();
        service.create(USER, new CircuitSessionRequest("  ", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(CircuitSession.class);
        verify(repository).save(captor.capture());
        assertEquals("Untitled circuit", captor.getValue().getTitle());
        assertEquals("{}", captor.getValue().getPayload());
        assertEquals(0, captor.getValue().getSubCount());
    }

    @Test
    void updateOnlyTouchesProvidedFields() {
        var existing = new CircuitSession();
        existing.setUserEmail(USER);
        existing.setTitle("Old title");
        existing.setPayload("{\"old\":true}");
        existing.setSubCount(2);
        stubSaveEcho();
        when(repository.findByIdAndUserEmail(7L, USER)).thenReturn(Optional.of(existing));

        service.update(USER, 7L, new CircuitSessionRequest(null, null, 5));

        assertEquals("Old title", existing.getTitle());
        assertEquals("{\"old\":true}", existing.getPayload());
        assertEquals(5, existing.getSubCount());
    }

    @Test
    void getThrowsWhenSessionBelongsToAnotherUser() {
        when(repository.findByIdAndUserEmail(9L, USER)).thenReturn(Optional.empty());

        assertThrows(CircuitSessionNotFoundException.class, () -> service.get(USER, 9L));
    }

    @Test
    void listReturnsSummariesWithoutPayload() {
        var s = new CircuitSession();
        s.setId(3L);
        s.setUserEmail(USER);
        s.setTitle("PM Roadmap");
        s.setPayload("{\"big\":\"blob\"}");
        s.setSubCount(4);
        when(repository.findByUserEmailOrderByUpdatedAtDesc(USER)).thenReturn(List.of(s));

        List<Map<String, Object>> list = service.list(USER);

        assertEquals(1, list.size());
        assertEquals("PM Roadmap", list.get(0).get("title"));
        assertEquals(4, list.get(0).get("subCount"));
        assertEquals(false, list.get(0).containsKey("payload"));
    }

    @Test
    void deleteRemovesOwnedSession() {
        var s = new CircuitSession();
        when(repository.findByIdAndUserEmail(4L, USER)).thenReturn(Optional.of(s));

        service.delete(USER, 4L);

        verify(repository).delete(s);
    }
}
