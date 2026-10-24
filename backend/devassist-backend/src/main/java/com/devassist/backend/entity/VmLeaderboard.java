package com.devassist.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vm_leaderboard")
public class VmLeaderboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String username;

    // Public-facing label for the leaderboard (full name or email prefix).
    // The username column stays the unique identity key (the user's email);
    // this is only what other users see, so we never expose the email itself.
    @Column(name = "display_name")
    private String displayName;

    private Integer solved = 0;
    private Integer attempted = 0;
    private Integer score = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public VmLeaderboard() {}

    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Integer getSolved() { return solved; }
    public void setSolved(Integer solved) { this.solved = solved; }

    public Integer getAttempted() { return attempted; }
    public void setAttempted(Integer attempted) { this.attempted = attempted; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
