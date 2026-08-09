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
 * A saved Circuit board session — one "circuit", i.e. a collection of
 * sub-circuits plus its chat transcript. payload holds JSON serialized as
 * text; the backend never inspects it, it only stores and returns it for the
 * frontend to restore the board.
 */
@Entity
@Table(name = "circuit_sessions")
public class CircuitSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(nullable = false)
    private String title;

    // JSON: {circuits:[{prompt,intro,title,viz,data,steps,...}], convo:[...]}
    @Column(nullable = false)
    private String payload;

    @Column(name = "sub_count", nullable = false)
    private int subCount;

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

    public String getTitle() { return title; }

    public String getPayload() { return payload; }

    public int getSubCount() { return subCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }

    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public void setTitle(String title) { this.title = title; }

    public void setPayload(String payload) { this.payload = payload; }

    public void setSubCount(int subCount) { this.subCount = subCount; }
}
