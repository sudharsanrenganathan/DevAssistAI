package com.devassist.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vm_problems")
public class VmProblem {

    @Id
    private String id; // e.g. "E007"

    private String title;

    private String difficulty; // EASY, MEDIUM, HARD

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String content; // full raw txt

    public VmProblem() {}

    public VmProblem(String id, String title, String difficulty, String description, String content) {
        this.id = id;
        this.title = title;
        this.difficulty = difficulty;
        this.description = description;
        this.content = content;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
