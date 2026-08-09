package com.devassist.backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

    /** Manually advanced clock so window-sliding is testable without sleeping. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-02T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsRequestsUpToTheLimitThenBlocks() {
        RateLimitService limiter = new RateLimitService(3, 10, 20, new MutableClock());

        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertFalse(limiter.tryConsumePlan("user@example.com"));
    }

    @Test
    void windowSlidesAfterAnHour() {
        MutableClock clock = new MutableClock();
        RateLimitService limiter = new RateLimitService(1, 10, 20, clock);

        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertFalse(limiter.tryConsumePlan("user@example.com"));

        clock.advance(Duration.ofMinutes(61));
        assertTrue(limiter.tryConsumePlan("user@example.com"));
    }

    @Test
    void usersAreLimitedIndependently() {
        RateLimitService limiter = new RateLimitService(1, 10, 20, new MutableClock());

        assertTrue(limiter.tryConsumePlan("first@example.com"));
        assertFalse(limiter.tryConsumePlan("first@example.com"));
        assertTrue(limiter.tryConsumePlan("second@example.com"));
    }

    @Test
    void planAndFileQuotasAreIndependent() {
        RateLimitService limiter = new RateLimitService(1, 2, 20, new MutableClock());

        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertFalse(limiter.tryConsumePlan("user@example.com"));

        assertTrue(limiter.tryConsumeFile("user@example.com"));
        assertTrue(limiter.tryConsumeFile("user@example.com"));
        assertFalse(limiter.tryConsumeFile("user@example.com"));
    }

    @Test
    void circuitQuotaIsIndependentAndBlocksAtLimit() {
        RateLimitService limiter = new RateLimitService(1, 1, 2, new MutableClock());

        assertTrue(limiter.tryConsumeCircuit("user@example.com"));
        assertTrue(limiter.tryConsumeCircuit("user@example.com"));
        assertFalse(limiter.tryConsumeCircuit("user@example.com"));

        // The circuit bucket must not eat into plan/file quotas.
        assertTrue(limiter.tryConsumePlan("user@example.com"));
        assertTrue(limiter.tryConsumeFile("user@example.com"));
    }
}
