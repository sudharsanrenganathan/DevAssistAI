package com.devassist.backend.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A saved Ignite (project builder) project. plan and files hold JSON
 * serialized as text — the backend never inspects them, it only stores and
 * returns them for the frontend to restore a session.
 */
@Entity
@Table(name = "ignite_projects")
public class IgniteProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(nullable = false)
    private String name;

    private String slug;

    private String summary;

    private String prompt;

    // JSON: {project:{name,slug,summary}, files:[{path,description}]}
    private String plan;

    // JSON: {path: content} — completed files only
    @Column(nullable = false)
    private String files;

    // generating | complete
    @Column(nullable = false)
    private String status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public String getUserEmail() { return userEmail; }

    public String getName() { return name; }

    public String getSlug() { return slug; }

    public String getSummary() { return summary; }

    public String getPrompt() { return prompt; }

    public String getPlan() { return plan; }

    public String getFiles() { return files; }

    public String getStatus() { return status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }

    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public void setName(String name) { this.name = name; }

    public void setSlug(String slug) { this.slug = slug; }

    public void setSummary(String summary) { this.summary = summary; }

    public void setPrompt(String prompt) { this.prompt = prompt; }

    public void setPlan(String plan) { this.plan = plan; }

    public void setFiles(String files) { this.files = files; }

    public void setStatus(String status) { this.status = status; }
}
