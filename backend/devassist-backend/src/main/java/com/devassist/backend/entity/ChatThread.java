package com.devassist.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "secret_chats")
public class ChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    // Supabase Auth UUID — used for per-user data isolation
    @Column(name = "supabase_user_id")
    private String supabaseUserId;

    private String title;

    private boolean isPinned = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "pin_order")
    private Long pinOrder;

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSupabaseUserId() { return supabaseUserId; }
    public void setSupabaseUserId(String supabaseUserId) { this.supabaseUserId = supabaseUserId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public boolean isPinned() { return isPinned; }
    public void setPinned(boolean pinned) { isPinned = pinned; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public Long getPinOrder() { return pinOrder; }
    public void setPinOrder(Long pinOrder) { this.pinOrder = pinOrder; }
}