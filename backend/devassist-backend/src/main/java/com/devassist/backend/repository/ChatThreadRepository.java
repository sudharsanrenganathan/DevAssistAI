package com.devassist.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;
import com.devassist.backend.entity.ChatThread;
import java.util.List;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {
    List<ChatThread> findBySupabaseUserId(String supabaseUserId, Sort sort);
}