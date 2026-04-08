package com.devassist.backend.service;

import com.devassist.backend.entity.User;
import com.devassist.backend.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    /**
     * Sync a Supabase Auth user to our local database.
     * Creates a new record if the user doesn't exist, or updates if they do.
     *
     * @param supabaseId Supabase Auth UUID (from JWT "sub" claim)
     * @param email User's email
     * @param name User's display name (from Google profile or signup form)
     * @param avatarUrl User's avatar URL (from Google profile)
     * @return The synced User entity
     */
    public User syncSupabaseUser(String supabaseId, String email, String name, String avatarUrl) {
        // First try to find by Supabase ID
        Optional<User> existing = userRepository.findBySupabaseId(supabaseId);

        if (existing.isPresent()) {
            User user = existing.get();

            // Update fields if provided
            if (name != null && !name.isEmpty()) {
                user.setName(name);
            }
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                user.setAvatarUrl(avatarUrl);
            }
            if (email != null && !email.isEmpty()) {
                user.setEmail(email);
            }

            return userRepository.save(user);
        }

        // Try to find by email (for users who existed before Supabase migration)
        Optional<User> byEmail = userRepository.findByEmail(email);

        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setSupabaseId(supabaseId);

            if (name != null && !name.isEmpty()) {
                user.setName(name);
            }
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                user.setAvatarUrl(avatarUrl);
            }

            return userRepository.save(user);
        }

        // Create new user
        User newUser = new User();
        newUser.setSupabaseId(supabaseId);
        newUser.setEmail(email);
        newUser.setName(name != null ? name : email.split("@")[0]);
        newUser.setAvatarUrl(avatarUrl);
        newUser.setProvider("email"); // Default; will be updated via /sync

        return userRepository.save(newUser);
    }

    // ========= LEGACY METHODS (kept but unused) =========

    /**
     * @deprecated Use syncSupabaseUser instead
     */
    public User register(User user) {
        throw new RuntimeException("Registration is now handled by Supabase Auth");
    }

    /**
     * @deprecated Use Supabase Auth instead
     */
    public String login(String email, String password) {
        throw new RuntimeException("Login is now handled by Supabase Auth");
    }
}