-- Migration: shared global token pool with per-user hard quota (2026-07-06).
-- Replaces the single token_usage table with two ledgers so the global pool is
-- an O(1) atomic counter instead of a SUM over user rows:
--   user_daily_usage   — one row per user per UTC day (user identity = email,
--                        which is this app's user id everywhere)
--   global_daily_usage — one row per UTC day for the whole app
-- Daily reset is automatic: a new date means new rows.
-- Apply manually in the Supabase SQL editor, or let the engine's deploy step
-- run it. Read/written only by the AI engine over DATABASE_URL.
-- The old token_usage table is intentionally left in place (drop separately
-- once the new ledgers are verified in production).

create table if not exists public.user_daily_usage (
    user_email        text        not null,
    usage_date        date        not null,
    prompt_tokens     bigint      not null default 0,
    completion_tokens bigint      not null default 0,
    total_tokens      bigint      not null default 0,
    updated_at        timestamptz not null default now(),
    primary key (user_email, usage_date)
);

create table if not exists public.global_daily_usage (
    usage_date              date        primary key,
    total_prompt_tokens     bigint      not null default 0,
    total_completion_tokens bigint      not null default 0,
    total_tokens            bigint      not null default 0,
    updated_at              timestamptz not null default now()
);

-- RLS on with no policies: the anon/authenticated PostgREST roles can't touch
-- the ledgers. The AI engine connects as the table owner, which bypasses RLS.
alter table public.user_daily_usage enable row level security;
alter table public.global_daily_usage enable row level security;

comment on table public.user_daily_usage is
    'Per-user daily LLM token ledger (all DevAssist AI features). One row per user per UTC day.';
comment on table public.global_daily_usage is
    'App-wide daily LLM token ledger. One row per UTC day; the shared global pool counter.';

-- Seed from the legacy token_usage ledger so usage already recorded today
-- keeps counting against today's budgets (prompt/completion split unknown → 0).
insert into public.user_daily_usage (user_email, usage_date, total_tokens)
select user_email, date, tokens_used
from public.token_usage
on conflict (user_email, usage_date) do nothing;

insert into public.global_daily_usage (usage_date, total_tokens)
select date, sum(tokens_used)
from public.token_usage
group by date
on conflict (usage_date) do nothing;
