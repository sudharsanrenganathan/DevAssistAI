package com.devassist.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.devassist.backend.entity.GlobalMessage;

public interface GlobalMessageRepository extends JpaRepository<GlobalMessage, Long> {

    List<GlobalMessage> findByChatIdOrderByTimestampAsc(Long chatId);

}
