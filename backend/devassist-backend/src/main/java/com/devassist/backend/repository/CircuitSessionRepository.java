package com.devassist.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devassist.backend.entity.CircuitSession;

public interface CircuitSessionRepository extends JpaRepository<CircuitSession, Long> {

    List<CircuitSession> findByUserEmailOrderByUpdatedAtDesc(String userEmail);

    // Ownership is enforced by always loading through (id, owner email).
    Optional<CircuitSession> findByIdAndUserEmail(Long id, String userEmail);
}
