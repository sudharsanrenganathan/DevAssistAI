package com.devassist.backend.service;

/**
 * The AI engine answered 429. Unlike {@link AiEngineBusyException} (message
 * only), this carries the engine's raw JSON body so controllers can pass it
 * through verbatim — the frontend needs the {@code status}/{@code scope}
 * fields to tell an exhausted daily token budget ("quota") from a momentary
 * provider rate limit ("busy").
 */
public class AiEngineRateLimitedException extends RuntimeException {

    private final String responseBody;

    public AiEngineRateLimitedException(String responseBody) {
        super("AI engine returned 429");
        this.responseBody = responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
