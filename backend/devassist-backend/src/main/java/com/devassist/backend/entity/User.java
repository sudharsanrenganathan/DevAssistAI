package com.devassist.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    private String password;

    private String name;

    // Supabase Auth user UUID
    @Column(name = "supabase_id", unique = true)
    private String supabaseId;

    // Google profile picture URL
    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    // Auth provider: "google", "email"
    @Column(name = "provider")
    private String provider;

    public User() {}

    public User(String name, String email, String password) {
        this.name = name;
        this.email = email;
        this.password = password;
    }

    // === GETTERS ===

    public Long getId() { return id; }

    public String getEmail() { return email; }

    public String getPassword() { return password; }

    public String getName() { return name; }

    public String getSupabaseId() { return supabaseId; }

    public String getAvatarUrl() { return avatarUrl; }

    public String getProvider() { return provider; }

    // === SETTERS ===

    public void setId(Long id) { this.id = id; }

    public void setEmail(String email) { this.email = email; }

    public void setPassword(String password) { this.password = password; }

    public void setName(String name) { this.name = name; }

    public void setSupabaseId(String supabaseId) { this.supabaseId = supabaseId; }

    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public void setProvider(String provider) { this.provider = provider; }
}