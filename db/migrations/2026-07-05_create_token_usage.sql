-- Migration: token_usage ledger for Ignite daily token budgets (2026-07-05).
-- Apply manually in the Supabase SQL editor (Dashboard → SQL Editor → run this file).
-- Read/written only by the AI engine over its direct DATABASE_URL connection.

create table if not exists public.token_usage (
    user_email  text   not null,
    date        date   not null,
    tokens_used bigint not null default 0,
    primary key (user_email, date)
);

-- RLS on with no policies: the anon/authenticated PostgREST roles can't touch
-- the ledger. The AI engine connects as the table owner, which bypasses RLS.
alter table public.token_usage enable row level security;

comment on table public.token_usage is
    'Daily LLM token ledger for the Ignite project builder. One row per user per UTC day.';
