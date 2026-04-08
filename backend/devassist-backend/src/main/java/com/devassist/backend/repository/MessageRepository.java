package com.devassist.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.devassist.backend.entity.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByChatIdOrderByTimestampAsc(Long chatId);

    // ✅ NEW — filter by source
    List<Message> findByChatIdAndSourceOrderByTimestampAsc(Long chatId, String source);

    // Debug — get all messages
    List<Message> findAllByOrderByIdAsc();
}