package com.devassist.backend.service;

/** The AI engine (or its LLM provider) is rate-limited or over its token budget. */
public class AiEngineBusyException extends AiEngineException {

    public AiEngineBusyException() {
        super("The project builder is busy right now. Please try again in a minute.");
    }

    /** Carries the engine's own 429 message (e.g. a daily-token-quota notice). */
    public AiEngineBusyException(String message) {
        super(message);
    }
}
