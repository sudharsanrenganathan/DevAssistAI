package com.devassist.backend.service;

/** A user hit their hourly quota for the Ignite project builder. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
