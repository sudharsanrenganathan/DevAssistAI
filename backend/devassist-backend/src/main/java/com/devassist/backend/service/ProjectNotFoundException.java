package com.devassist.backend.service;

/** The requested saved project does not exist or belongs to another user. */
public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(Long id) {
        super("Project " + id + " not found");
    }
}
