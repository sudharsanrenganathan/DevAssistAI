package com.devassist.backend.controller;

import com.devassist.backend.entity.User;
import com.devassist.backend.service.AuthService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * GET /api/auth/me
     * Returns the current user's info based on the Supabase JWT.
     * The JwtFilter extracts email (principal) and supabaseId (credentials).
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String email = (String) auth.getPrincipal();
        String supabaseId = (String) auth.getCredentials();

        // Sync user to our database (create if new, update if existing)
        User user = authService.syncSupabaseUser(supabaseId, email, null, null);

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail());
        response.put("name", user.getName());
        response.put("supabaseId", user.getSupabaseId());
        response.put("avatarUrl", user.getAvatarUrl());
        response.put("provider", user.getProvider());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/sync
     * Explicitly sync user data (name, avatar) from frontend to backend.
     * Called after login to ensure user record exists with latest info.
     */
    @PostMapping("/sync")
    public ResponseEntity<?> syncUser(@RequestBody Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String email = (String) auth.getPrincipal();
        String supabaseId = (String) auth.getCredentials();
        String name = body.get("name");
        String avatarUrl = body.get("avatarUrl");
        String provider = body.get("provider");

        User user = authService.syncSupabaseUser(supabaseId, email, name, avatarUrl);
        if (provider != null) {
            user.setProvider(provider);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail());
        response.put("name", user.getName());
        response.put("supabaseId", user.getSupabaseId());
        response.put("avatarUrl", user.getAvatarUrl());
        response.put("provider", user.getProvider());

        return ResponseEntity.ok(response);
    }
}