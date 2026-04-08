package com.devassist.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "session_chats")
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Supabase UUID stored directly (text column in DB)
    @Column(name = "user_id")
    private String userId;

    @ManyToOne
    @JoinColumn(name = "session_id")
    private ChatSession session;

    private String question;

    @Column(length = 5000)
    private String answer;

    private LocalDateTime timestamp;

    // GETTERS
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public ChatSession getSession() { return session; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public LocalDateTime getTimestamp() { return timestamp; }

    // SETTERS
    public void setUserId(String userId) { this.userId = userId; }
    public void setSession(ChatSession session) { this.session = session; }
    public void setQuestion(String question) { this.question = question; }
    public void setAnswer(String answer) { this.answer = answer; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}