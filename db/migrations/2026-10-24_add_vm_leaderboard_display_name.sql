-- Migration: per-user isolation for VoidMain (2026-10-24).
--
-- Context: VoidMain previously keyed submissions and the leaderboard on a
-- client-supplied "username" that the frontend never set, so every user shared
-- the single value "guest" and saw each other's history. The backend now keys
-- on the authenticated user's email (from the verified Supabase JWT) instead.
--
-- The vm_submissions.username and vm_leaderboard.username columns are REUSED to
-- hold the email (no schema change there — old "guest" rows simply become
-- orphaned and invisible to every real account). This migration only adds a
-- public-facing display label to the leaderboard, so other users never see raw
-- email addresses on the public board.
--
-- Read/written by the Spring Boot backend (JPA) over SPRING_DATASOURCE_URL.
-- ⚠ APPLY THIS TO SUPABASE BEFORE DEPLOYING THE NEW BACKEND — ddl-auto=none, so
--   the app will reference vm_leaderboard.display_name immediately on startup.

alter table public.vm_leaderboard
    add column if not exists display_name text;

-- Backfill existing rows with the email prefix so historical entries still
-- render a friendly label until their owner next submits (which refreshes it
-- with the real profile display name sent by the client).
update public.vm_leaderboard
    set display_name = split_part(username, '@', 1)
    where display_name is null;
