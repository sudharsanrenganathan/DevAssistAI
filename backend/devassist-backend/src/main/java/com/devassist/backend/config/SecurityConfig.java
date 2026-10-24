package com.devassist.backend.config;

import com.devassist.backend.security.JwtFilter;
import com.devassist.backend.security.JwtUtil;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        JwtFilter jwtFilter = new JwtFilter(jwtUtil);

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable()) // CSRF disabled for all endpoints
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // ASYNC and ERROR re-dispatches carry no Authentication: JwtFilter
                // only authenticates the initial REQUEST dispatch, which has already
                // been authorized. Without ASYNC, StreamingResponseBody endpoints
                // (e.g. /api/project-builder/generate-file) 403 mid-flight; without
                // ERROR, the /error page is denied and every 404/500 surfaces to
                // clients as an empty 403. Neither dispatch type can be initiated
                // by a client — the container triggers them internally.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()

                // ===== TOKEN-CONSUMING AI ROUTES (JWT required) =====
                // These proxy to the LLM and charge the authenticated user's
                // daily token budget, so the caller's identity is mandatory.
                // Matched BEFORE the broader permit-all patterns below.
                .requestMatchers(
                    "/api/global-chat/ask",
                    "/api/global-chat/local-ask",
                    "/api/circuit/**",
                    "/ai/rag", "/ai/rag/**",
                    "/ai/ask/**",
                    "/ai/analyze"
                ).authenticated()

                // ===== PUBLIC API ROUTES (no auth required) =====
                // NOTE: the AI engine itself loads chat history via
                // GET /api/global-chat/{id} and /ai/messages/{id} without a
                // JWT, so these subtrees must stay public apart from the
                // token-consuming endpoints matched above.
                .requestMatchers(
                    "/api/global-chat/**",
                    "/api/upload-doc",
                    "/api/activity/**",
                    "/ai/**"
                ).permitAll()

                // ===== VOIDMAIN (JWT required) =====
                // Every /vm endpoint derives its user from the authenticated
                // principal (email), so the whole subtree needs a valid JWT —
                // including the leaderboard, which is still "public" in that it
                // ranks all users, but is only viewable by logged-in users (the
                // VoidMain pages all require auth) and never exposes emails.
                .requestMatchers("/vm/**").authenticated()
                
                // ===== PUBLIC STATIC ROUTES =====
                .requestMatchers(
                    "/", "/index.html",
                    "/login.html", "/signup.html",
                    "/dashboard.html", "/auth-guard.js",
                    "/favicon.ico",
                    "/*.css", "/*.js", "/*.png", "/*.jpg", "/*.svg", "/*.ico", "/*.html",
                    "/img/**"
                ).permitAll()
                
                // ===== OTHER PUBLIC ROUTES =====
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/health", "/health").permitAll()
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