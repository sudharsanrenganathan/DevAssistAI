package com.devassist.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devassist.backend.entity.IgniteProject;

public interface IgniteProjectRepository extends JpaRepository<IgniteProject, Long> {

    List<IgniteProject> findByUserEmailOrderByUpdatedAtDesc(String userEmail);

    // Ownership is enforced by always loading through (id, owner email).
    Optional<IgniteProject> findByIdAndUserEmail(Long id, String userEmail);
}
