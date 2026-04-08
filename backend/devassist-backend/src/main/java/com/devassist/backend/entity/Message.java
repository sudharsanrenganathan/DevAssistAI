package com.devassist.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id")
    private Long chatId;

    private String source; // "secret" or "global"

    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime timestamp = LocalDateTime.now();
    public Long getId() {
    return id;
}

public Long getChatId() {
    return chatId;
}

public void setChatId(Long chatId) {
    this.chatId = chatId;
}

public String getRole() {
    return role;
}

public void setRole(String role) {
    this.role = role;
}

public String getContent() {
    return content;
}

public void setContent(String content) {
    this.content = content;
}

public LocalDateTime getTimestamp() {
    return timestamp;
}
public String getSource() { return source; }
public void setSource(String source) { this.source = source; }
}