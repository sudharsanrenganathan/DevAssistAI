package com.devassist.backend.config;

import com.devassist.backend.security.JwtFilter;
import com.devassist.backend.security.JwtUtil;

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
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // ===== PUBLIC API ROUTES (no auth required) =====
                .requestMatchers(
                    "/api/rag",
                    "/api/global-chat/**",
                    "/api/upload-doc",
                    "/ai/**"
                ).permitAll()
                
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