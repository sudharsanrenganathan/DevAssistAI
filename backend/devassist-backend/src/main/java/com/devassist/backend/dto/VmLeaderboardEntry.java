package com.devassist.backend.dto;

/**
 * Public leaderboard row for GET /vm/leaderboard.
 *
 * <p>Deliberately omits the user's email (the internal identity key) so the
 * public board never exposes it. {@code displayName} is the friendly label and
 * {@code you} marks the authenticated caller's own row for client highlighting.
 */
public record VmLeaderboardEntry(
        String displayName,
        int solved,
        int attempted,
        int score,
        boolean you) {
}
