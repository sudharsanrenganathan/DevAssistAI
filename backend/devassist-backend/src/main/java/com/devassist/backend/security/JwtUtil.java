package com.devassist.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates and parses Supabase-issued JWT tokens.
 *
 * Supabase projects use one of two signing schemes, so both are supported:
 * - Legacy HS256: shared secret from Dashboard > Settings > API (supabase.jwt-secret)
 * - Current ES256: asymmetric keys published at /auth/v1/.well-known/jwks.json,
 *   selected per token via the "kid" header and cached in memory
 */
@Component
public class JwtUtil {

    private final SecretKey legacySecretKey;
    private final String jwksUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, PublicKey> jwksKeys = new ConcurrentHashMap<>();

    public JwtUtil(@Value("${supabase.jwt-secret}") String jwtSecret,
                   @Value("${supabase.url}") String supabaseUrl,
                   RestTemplate restTemplate) {
        this.legacySecretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.jwksUrl = supabaseUrl + "/auth/v1/.well-known/jwks.json";
        this.restTemplate = restTemplate;
    }

    /**
     * Parse and validate a Supabase JWT token.
     * @param token the JWT string (without "Bearer " prefix)
     * @return Claims if valid
     * @throws JwtException if the token is invalid, expired, or no key matches
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                    @Override
                    public Key resolveSigningKey(JwsHeader header, Claims claims) {
                        String algorithm = header.getAlgorithm();
                        if (algorithm != null && algorithm.startsWith("HS")) {
                            return legacySecretKey;
                        }
                        return publicKeyFor(header.getKeyId());
                    }
                })
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key publicKeyFor(String kid) {
        if (kid == null) {
            throw new JwtException("Asymmetric token is missing the kid header");
        }
        PublicKey key = jwksKeys.get(kid);
        if (key == null) {
            refreshJwks(); // unknown kid — Supabase may have rotated keys
            key = jwksKeys.get(kid);
        }
        if (key == null) {
            throw new JwtException("No Supabase signing key found for kid=" + kid);
        }
        return key;
    }

    private synchronized void refreshJwks() {
        try {
            String body = restTemplate.getForObject(jwksUrl, String.class);
            JsonNode keys = objectMapper.readTree(body).path("keys");
            for (JsonNode jwk : keys) {
                if (!"EC".equals(jwk.path("kty").asText())) {
                    continue;
                }
                jwksKeys.put(jwk.path("kid").asText(),
                        ecPublicKeyFrom(jwk.path("x").asText(), jwk.path("y").asText()));
            }
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("Could not load Supabase JWKS: " + e.getMessage(), e);
        }
    }

    /** Builds a P-256 public key from a JWK's base64url x/y coordinates. */
    private static PublicKey ecPublicKeyFrom(String x, String y) throws GeneralSecurityException {
        BigInteger xi = new BigInteger(1, Base64.getUrlDecoder().decode(x));
        BigInteger yi = new BigInteger(1, Base64.getUrlDecoder().decode(y));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(xi, yi), spec));
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
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
