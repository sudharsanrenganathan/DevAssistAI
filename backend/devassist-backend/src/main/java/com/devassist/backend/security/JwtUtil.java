package com.devassist.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Utility to validate and parse Supabase-issued JWT tokens.
 * Uses the JWT Secret from Supabase Dashboard > Settings > API.
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    public JwtUtil(@Value("${supabase.jwt-secret}") String jwtSecret) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse and validate a Supabase JWT token.
     * @param token the JWT string (without "Bearer " prefix)
     * @return Claims if valid
     * @throws Exception if token is invalid or expired
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract the user's email from a Supabase JWT.
     */
    public String extractEmail(String token) {
        return parseToken(token).get("email", String.class);
    }

    /**
     * Extract the Supabase user UUID (the "sub" claim).
     */
    public String extractSubject(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * Extract the user's role from the JWT (e.g., "authenticated").
     */
    public String extractRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**
     * Check if the token is valid (not expired, correctly signed).
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseToken(token);
            return "authenticated".equals(claims.get("role", String.class));
        } catch (Exception e) {
            return false;
        }
    }
}