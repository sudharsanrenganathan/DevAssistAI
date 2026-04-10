package com.devassist.backend.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT Authentication Filter for Supabase tokens.
 * Validates the Bearer token, extracts user info (email, supabaseId),
 * and sets the Spring Security authentication context.
 */
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean shouldSkip = path.startsWith("/ai/") || 
               path.startsWith("/api/auth/") || 
               path.startsWith("/api/global-chat/") ||
               path.startsWith("/api/chat/") ||
               path.equals("/api/upload-doc") ||
               path.equals("/health") ||
               path.equals("/api/health") ||
               path.equals("/");
        
        if (shouldSkip) {
            System.out.println("✅ JWT Filter SKIPPED for path: " + path);
        }
        
        return shouldSkip;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                Claims claims = jwtUtil.parseToken(token);
                String email = claims.get("email", String.class);
                String supabaseId = claims.getSubject(); // UUID
                String role = claims.get("role", String.class);

                if ("authenticated".equals(role) && email != null) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    email,               // principal = email
                                    supabaseId,          // credentials = supabase UUID
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
                            );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                // Invalid token — don't set auth, let Spring Security deny access
                System.out.println("⚠ JWT validation failed: " + e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}