package com.devassist.backend.service;

/** The AI engine returned an error or could not be reached. */
public class AiEngineException extends RuntimeException {

    public AiEngineException(String message) {
        super(message);
    }
}
