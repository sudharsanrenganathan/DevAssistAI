package com.devassist.backend.config;

import com.devassist.backend.security.JwtFilter;
import com.devassist.backend.security.JwtUtil;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * Security filter chain for /ai/** endpoints - completely public, no authentication required
     */
    @Bean
    @Order(1)
    public SecurityFilterChain aiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/ai/**")
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );
        
        return http.build();
    }

    /**
     * Main security filter chain for all other endpoints
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        JwtFilter jwtFilter = new JwtFilter(jwtUtil);

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // ===== PUBLIC ROUTES (no auth required) =====
                // Static pages: login, signup, auth-guard, etc.
                .requestMatchers(
                    "/", "/index.html",
                    "/login.html", "/signup.html",
                    "/dashboard.html", "/auth-guard.js",
                    "/favicon.ico",
                    "/*.css", "/*.js", "/*.png", "/*.jpg", "/*.svg", "/*.ico", "/*.html",
                    "/img/**"
                ).permitAll()
                // Auth API endpoints
                .requestMatchers("/api/auth/**").permitAll()
                // Health check
                .requestMatchers("/api/health", "/health").permitAll()
                // Global Chat endpoints
                .requestMatchers("/api/global-chat/**").permitAll()
                // Chat endpoints
                .requestMatchers("/api/chat/**").permitAll()

                // ===== PROTECTED ROUTES (require valid JWT) =====
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            // Add JWT filter BEFORE Spring's auth filter
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}