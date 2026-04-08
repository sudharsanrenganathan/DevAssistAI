package com.devassist.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.devassist.backend.entity.SecretMessage;

public interface SecretMessageRepository extends JpaRepository<SecretMessage, Long> {

    List<SecretMessage> findByChatIdOrderByTimestampAsc(Long chatId);

}
