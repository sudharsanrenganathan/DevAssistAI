package com.devassist.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-memory per-user sliding-window rate limiter for the LLM-backed features
 * (Ignite project builder, Circuit). Free-tier friendly by design: no Redis or
 * external store. State resets on restart, which is acceptable — its job is
 * protecting the limited LLM credits from a single user draining them, not
 * perfect accounting.
 */
@Service
public class RateLimitService {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> planHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> fileHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> circuitHits = new ConcurrentHashMap<>();

    private final int plansPerHour;
    private final int filesPerHour;
    private final int circuitAsksPerHour;
    private final Clock clock;

    @Autowired
    public RateLimitService(
            @Value("${ignite.rate-limit.plans-per-hour:6}") int plansPerHour,
            @Value("${ignite.rate-limit.files-per-hour:120}") int filesPerHour,
            @Value("${circuit.rate-limit.asks-per-hour:20}") int circuitAsksPerHour) {
        this(plansPerHour, filesPerHour, circuitAsksPerHour, Clock.systemUTC());
    }

    RateLimitService(int plansPerHour, int filesPerHour, int circuitAsksPerHour, Clock clock) {
        this.plansPerHour = plansPerHour;
        this.filesPerHour = filesPerHour;
        this.circuitAsksPerHour = circuitAsksPerHour;
        this.clock = clock;
    }

    /** One plan request = one new project generation started. */
    public boolean tryConsumePlan(String userId) {
        return tryConsume(planHits, userId, plansPerHour);
    }

    /** One file request = one generated (or retried) file. */
    public boolean tryConsumeFile(String userId) {
        return tryConsume(fileHits, userId, filesPerHour);
    }

    /** One circuit ask = one visual explanation generated. */
    public boolean tryConsumeCircuit(String userId) {
        return tryConsume(circuitHits, userId, circuitAsksPerHour);
    }

    private boolean tryConsume(Map<String, Deque<Instant>> hits, String userId, int limit) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> window = hits.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
