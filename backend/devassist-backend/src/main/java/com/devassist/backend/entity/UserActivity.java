package com.devassist.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_activity")
public class UserActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supabase_user_id")
    private String supabaseUserId;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "page_url")
    private String pageUrl;

    @Column(name = "icon")
    private String icon;

    private LocalDateTime timestamp;

    public UserActivity() {}

    public UserActivity(String supabaseUserId, String toolName, String pageUrl, String icon) {
        this.supabaseUserId = supabaseUserId;
        this.toolName = toolName;
        this.pageUrl = pageUrl;
        this.icon = icon;
        this.timestamp = LocalDateTime.now();
    }

    // === GETTERS ===
    public Long getId() { return id; }
    public String getSupabaseUserId() { return supabaseUserId; }
    public String getToolName() { return toolName; }
    public String getPageUrl() { return pageUrl; }
    public String getIcon() { return icon; }
    public LocalDateTime getTimestamp() { return timestamp; }

    // === SETTERS ===
    public void setId(Long id) { this.id = id; }
    public void setSupabaseUserId(String supabaseUserId) { this.supabaseUserId = supabaseUserId; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public void setIcon(String icon) { this.icon = icon; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
