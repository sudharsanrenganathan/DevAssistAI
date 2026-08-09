# services/token_ledger.py — shared global token pool with per-user hard quota,
# covering ALL LLM features (Global AI, doc chat, code analyzer, Ignite).
#
# Model: nothing is pre-allocated. Tokens are deducted from the shared global
# pool only after the provider reports what a call actually consumed. The
# per-user limit is just the ceiling one user may consume in a single UTC day.
#
#   provider daily limit   AI_PROVIDER_DAILY_TOKENS    default 200 000
#   emergency reserve      AI_EMERGENCY_RESERVE_TOKENS default  10 000  (never spent)
#   usable global pool     AI_GLOBAL_DAILY_TOKENS      default provider − reserve = 190 000
#   per-user daily limit   AI_USER_DAILY_TOKENS        default  25 000
#
# (Legacy PROJECT_BUILDER_*_DAILY_TOKENS names are honored as fallbacks so
# existing deployments keep working until their env vars are renamed.)
#
# Ledgers (migration: db/migrations/2026-07-06_create_daily_usage_tables.sql):
#   user_daily_usage(user_email, usage_date, prompt/completion/total, updated_at)
#   global_daily_usage(usage_date, totals, updated_at)
# One row per UTC day — a new date is automatically a fresh budget, no reset
# job needed. record_usage() upserts BOTH rows in a single transaction with
# additive updates (x = x + delta), so concurrent requests cannot lose counts.
#
# Enforcement is pre-flight: check_budget() rejects a request whose estimated
# size (prompt chars/4 + reserved max_tokens) would push the user over their
# quota or the app over the usable pool. Because metering is post-paid, calls
# already in flight can overshoot slightly — that is exactly what the
# emergency reserve absorbs, which is why it must never be spent.
#
# The ledger FAILS OPEN: if the database is unreachable the features keep
# working — the budget protects free-tier credits, it must never take the
# app down.

import os
from datetime import datetime, timezone
from typing import Optional

SERVICE_NAME = "DevAssist AI"

# Ledger key used when a request carries no user email (e.g. an old client);
# per-user checks are skipped but the tokens still count against the global pool.
UNKNOWN_USER = "(unknown)"


class QuotaExceededError(Exception):
    """A daily token budget is exhausted. scope is 'user' or 'global'."""

    def __init__(self, scope: str, message: str):
        super().__init__(message)
        self.scope = scope


def _env_int(value: Optional[str], fallback: int) -> int:
    try:
        return int(value) if value is not None else fallback
    except ValueError:
        return fallback


def provider_daily_limit() -> int:
    return _env_int(os.getenv("AI_PROVIDER_DAILY_TOKENS"), 200000)


def emergency_reserve() -> int:
    return _env_int(os.getenv("AI_EMERGENCY_RESERVE_TOKENS"), 10000)


def user_daily_limit() -> int:
    value = os.getenv("AI_USER_DAILY_TOKENS",
                      os.getenv("PROJECT_BUILDER_USER_DAILY_TOKENS"))
    return _env_int(value, 25000)


def global_daily_limit() -> int:
    """The usable pool: provider limit minus the untouchable reserve, unless
    explicitly overridden."""
    value = os.getenv("AI_GLOBAL_DAILY_TOKENS",
                      os.getenv("PROJECT_BUILDER_GLOBAL_DAILY_TOKENS"))
    return _env_int(value, provider_daily_limit() - emergency_reserve())


def estimate_tokens(prompt_chars: int, max_tokens: int) -> int:
    """Pre-flight request size: ~4 chars per prompt token plus the reserved
    completion budget. Deliberately pessimistic — same rule Groq's TPM uses."""
    return prompt_chars // 4 + max_tokens


def _today() -> str:
    # UTC keeps the reset aligned with the provider's daily windows regardless
    # of server or user timezone.
    return datetime.now(timezone.utc).date().isoformat()


def _connect():
    import psycopg2
    return psycopg2.connect(os.getenv("DATABASE_URL"))


def get_usage(user_email: str) -> dict:
    """Today's token usage for one user and for the whole app, plus limits.
    Raises on database errors — callers decide whether to fail open."""
    email = (user_email or "").strip().lower() or UNKNOWN_USER
    today = _today()
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT COALESCE(prompt_tokens, 0), COALESCE(completion_tokens, 0),
                   COALESCE(total_tokens, 0)
            FROM user_daily_usage
            WHERE user_email = %s AND usage_date = %s
            """,
            (email, today),
        )
        row = cur.fetchone() or (0, 0, 0)
        user_prompt, user_completion, user_total = row
        cur.execute(
            """
            SELECT COALESCE(total_prompt_tokens, 0), COALESCE(total_completion_tokens, 0),
                   COALESCE(total_tokens, 0)
            FROM global_daily_usage
            WHERE usage_date = %s
            """,
            (today,),
        )
        row = cur.fetchone() or (0, 0, 0)
        global_prompt, global_completion, global_total = row
        cur.close()
    finally:
        conn.close()
    return {
        "date": today,
        # Existing keys — the frontend meter and /api/usage consumers rely on
        # these names; do not rename.
        "user_tokens": int(user_total),
        "user_limit": user_daily_limit(),
        "global_tokens": int(global_total),
        "global_limit": global_daily_limit(),
        # Breakdown added with the shared-pool ledger.
        "user_prompt_tokens": int(user_prompt),
        "user_completion_tokens": int(user_completion),
        "global_prompt_tokens": int(global_prompt),
        "global_completion_tokens": int(global_completion),
        "emergency_reserve": emergency_reserve(),
    }


def check_budget(user_email: str, estimated_tokens: int = 0) -> None:
    """Raise QuotaExceededError if this request would exceed today's budgets.
    Called BEFORE every provider call; estimated_tokens is the pre-flight size
    from estimate_tokens() (0 = only reject when already at the limit).
    Database problems fail open (allow the call)."""
    try:
        usage = get_usage(user_email)
    except Exception as e:
        print(f"⚠ token_ledger.check_budget failed open: {e}")
        return

    estimate = max(0, int(estimated_tokens))
    if usage["global_tokens"] + estimate > usage["global_limit"] \
            or usage["global_tokens"] >= usage["global_limit"]:
        raise QuotaExceededError(
            "global",
            f"{SERVICE_NAME} has reached its app-wide daily capacity. "
            f"Please try again tomorrow.",
        )
    if (user_email or "").strip() and (
            usage["user_tokens"] + estimate > usage["user_limit"]
            or usage["user_tokens"] >= usage["user_limit"]):
        remaining = max(0, usage["user_limit"] - usage["user_tokens"])
        raise QuotaExceededError(
            "user",
            f"This request would exceed your daily {SERVICE_NAME} allowance "
            f"({usage['user_limit']:,} tokens/day, {remaining:,} left). "
            f"It resets at midnight UTC.",
        )


def record_usage(user_email: str, usage: Optional[dict]) -> None:
    """Add one provider call's reported usage to today's ledgers. Both the
    user row and the global row are updated additively in ONE transaction, so
    concurrent requests can never lose counts. usage is the provider's dict
    ({prompt_tokens, completion_tokens, total_tokens}) or None when the
    provider reported nothing."""
    usage = usage or {}
    prompt = max(0, int(usage.get("prompt_tokens") or 0))
    completion = max(0, int(usage.get("completion_tokens") or 0))
    total = int(usage.get("total_tokens") or 0) or (prompt + completion)
    if total <= 0:
        return
    email = (user_email or "").strip().lower() or UNKNOWN_USER
    today = _today()
    try:
        conn = _connect()
        try:
            cur = conn.cursor()
            cur.execute(
                """
                INSERT INTO user_daily_usage
                    (user_email, usage_date, prompt_tokens, completion_tokens, total_tokens)
                VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (user_email, usage_date) DO UPDATE SET
                    prompt_tokens     = user_daily_usage.prompt_tokens     + EXCLUDED.prompt_tokens,
                    completion_tokens = user_daily_usage.completion_tokens + EXCLUDED.completion_tokens,
                    total_tokens      = user_daily_usage.total_tokens      + EXCLUDED.total_tokens,
                    updated_at        = now()
                """,
                (email, today, prompt, completion, total),
            )
            cur.execute(
                """
                INSERT INTO global_daily_usage
                    (usage_date, total_prompt_tokens, total_completion_tokens, total_tokens)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (usage_date) DO UPDATE SET
                    total_prompt_tokens     = global_daily_usage.total_prompt_tokens     + EXCLUDED.total_prompt_tokens,
                    total_completion_tokens = global_daily_usage.total_completion_tokens + EXCLUDED.total_completion_tokens,
                    total_tokens            = global_daily_usage.total_tokens            + EXCLUDED.total_tokens,
                    updated_at              = now()
                """,
                (today, prompt, completion, total),
            )
            conn.commit()  # both rows or neither — the ledgers never drift apart
            cur.close()
        finally:
            conn.close()
    except Exception as e:
        print(f"⚠ token_ledger.record_usage error (usage lost, not fatal): {e}")
