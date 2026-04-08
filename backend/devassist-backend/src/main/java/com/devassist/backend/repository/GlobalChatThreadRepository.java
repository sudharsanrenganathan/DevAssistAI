package com.devassist.backend.repository;

import com.devassist.backend.entity.GlobalChatThread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Sort;
import java.util.List;

public interface GlobalChatThreadRepository extends JpaRepository<GlobalChatThread, Long> {
    List<GlobalChatThread> findAll(Sort sort);
    List<GlobalChatThread> findBySupabaseUserId(String supabaseUserId, Sort sort);
}