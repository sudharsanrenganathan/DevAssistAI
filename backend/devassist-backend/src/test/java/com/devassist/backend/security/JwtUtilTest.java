package com.devassist.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

class JwtUtilTest {

    private static final String SHARED_SECRET = "test-shared-secret-that-is-long-enough!!";
    private static final String SUPABASE_URL = "https://example.supabase.co";

    private static KeyPair supabaseKeyPair;
    private static KeyPair attackerKeyPair;

    private RestTemplate restTemplate;
    private JwtUtil jwtUtil;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        supabaseKeyPair = generator.generateKeyPair();
        attackerKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(jwksJson((ECPublicKey) supabaseKeyPair.getPublic(), "key-1"));
        jwtUtil = new JwtUtil(SHARED_SECRET, SUPABASE_URL, restTemplate);
    }

    private static String jwksJson(ECPublicKey publicKey, String kid) {
        String x = base64UrlCoordinate(publicKey.getW().getAffineX());
        String y = base64UrlCoordinate(publicKey.getW().getAffineY());
        return "{\"keys\":[{\"kty\":\"EC\",\"crv\":\"P-256\",\"alg\":\"ES256\","
                + "\"kid\":\"" + kid + "\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}]}";
    }

    /** JWK EC coordinates are fixed-length 32-byte base64url values. */
    private static String base64UrlCoordinate(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int copyLength = Math.min(32, raw.length);
        System.arraycopy(raw, raw.length - copyLength, fixed, 32 - copyLength, copyLength);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static String es256Token(KeyPair keyPair, String kid) {
        return Jwts.builder()
                .setHeaderParam("kid", kid)
                .setSubject("user-uuid-123")
                .claim("email", "user@example.com")
                .claim("role", "authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.ES256)
                .compact();
    }

    @Test
    void parsesLegacyHs256TokenSignedWithSharedSecret() {
        String token = Jwts.builder()
                .setSubject("user-uuid-123")
                .claim("email", "user@example.com")
                .claim("role", "authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SHARED_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = jwtUtil.parseToken(token);

        assertEquals("user@example.com", claims.get("email", String.class));
        assertEquals("user-uuid-123", claims.getSubject());
    }

    @Test
    void parsesEs256TokenUsingJwksPublicKey() {
        Claims claims = jwtUtil.parseToken(es256Token(supabaseKeyPair, "key-1"));

        assertEquals("user@example.com", claims.get("email", String.class));
        assertEquals("authenticated", claims.get("role", String.class));
    }

    @Test
    void rejectsEs256TokenSignedWithWrongKey() {
        String forged = es256Token(attackerKeyPair, "key-1");

        assertThrows(Exception.class, () -> jwtUtil.parseToken(forged));
        assertFalse(jwtUtil.isTokenValid(forged));
    }

    @Test
    void rejectsEs256TokenWithUnknownKid() {
        String token = es256Token(supabaseKeyPair, "unknown-kid");

        assertThrows(JwtException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    void rejectsExpiredToken() {
        String expired = Jwts.builder()
                .setHeaderParam("kid", "key-1")
                .setSubject("user-uuid-123")
                .setExpiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(supabaseKeyPair.getPrivate(), SignatureAlgorithm.ES256)
                .compact();

        assertFalse(jwtUtil.isTokenValid(expired));
    }

    @Test
    void isTokenValidReturnsTrueForGoodToken() {
        assertTrue(jwtUtil.isTokenValid(es256Token(supabaseKeyPair, "key-1")));
    }
}
