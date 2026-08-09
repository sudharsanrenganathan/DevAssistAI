# Tests for services/token_ledger.py — shared global pool with per-user hard
# quota. The database is always faked.

import pytest

from services import token_ledger


class FakeCursor:
    """Serves get_usage's two SELECTs (user row, then global row) and records
    every executed statement for assertions."""

    def __init__(self, user_row=(0, 0, 0), global_row=(0, 0, 0)):
        self._select_rows = [user_row, global_row]
        self._select_i = 0
        self.executed = []

    def execute(self, sql, params=None):
        self.executed.append((" ".join(sql.split()), params))

    def fetchone(self):
        row = None
        if self._select_i < len(self._select_rows):
            row = self._select_rows[self._select_i]
        self._select_i += 1
        return row

    def close(self):
        pass


class FakeConn:
    def __init__(self, cursor):
        self._cursor = cursor
        self.commits = 0
        self.closed = False

    def cursor(self):
        return self._cursor

    def commit(self):
        self.commits += 1

    def close(self):
        self.closed = True


def use_db(monkeypatch, user_total=0, global_total=0,
           user_row=None, global_row=None):
    """Fake _connect(); rows are (prompt, completion, total)."""
    cursor = FakeCursor(user_row or (0, 0, user_total),
                        global_row or (0, 0, global_total))
    conn = FakeConn(cursor)
    monkeypatch.setattr(token_ledger, "_connect", lambda: conn)
    return cursor, conn


@pytest.fixture(autouse=True)
def default_limits(monkeypatch):
    # A developer's .env / shell must not leak budgets into these tests.
    for var in ("AI_PROVIDER_DAILY_TOKENS", "AI_EMERGENCY_RESERVE_TOKENS",
                "AI_USER_DAILY_TOKENS", "AI_GLOBAL_DAILY_TOKENS",
                "PROJECT_BUILDER_USER_DAILY_TOKENS",
                "PROJECT_BUILDER_GLOBAL_DAILY_TOKENS"):
        monkeypatch.delenv(var, raising=False)


# ---------------- configuration ----------------

def test_default_limits_match_the_spec():
    assert token_ledger.provider_daily_limit() == 200000
    assert token_ledger.emergency_reserve() == 10000
    assert token_ledger.global_daily_limit() == 190000  # provider − reserve
    assert token_ledger.user_daily_limit() == 25000


def test_global_pool_derives_from_provider_minus_reserve(monkeypatch):
    monkeypatch.setenv("AI_PROVIDER_DAILY_TOKENS", "100000")
    monkeypatch.setenv("AI_EMERGENCY_RESERVE_TOKENS", "5000")
    assert token_ledger.global_daily_limit() == 95000


def test_limits_are_env_tunable_with_explicit_override(monkeypatch):
    monkeypatch.setenv("AI_USER_DAILY_TOKENS", "7000")
    monkeypatch.setenv("AI_GLOBAL_DAILY_TOKENS", "50000")
    assert token_ledger.user_daily_limit() == 7000
    assert token_ledger.global_daily_limit() == 50000


def test_legacy_env_names_still_work(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_USER_DAILY_TOKENS", "9000")
    monkeypatch.setenv("PROJECT_BUILDER_GLOBAL_DAILY_TOKENS", "80000")
    assert token_ledger.user_daily_limit() == 9000
    assert token_ledger.global_daily_limit() == 80000


def test_estimate_tokens_is_chars_over_four_plus_completion():
    assert token_ledger.estimate_tokens(4000, 1024) == 2024


# ---------------- get_usage ----------------

def test_get_usage_returns_totals_breakdown_and_limits(monkeypatch):
    use_db(monkeypatch, user_row=(800, 400, 1200), global_row=(30000, 15000, 45000))
    usage = token_ledger.get_usage("user@example.com")
    assert usage["user_tokens"] == 1200
    assert usage["global_tokens"] == 45000
    assert usage["user_prompt_tokens"] == 800
    assert usage["user_completion_tokens"] == 400
    assert usage["global_prompt_tokens"] == 30000
    assert usage["global_completion_tokens"] == 15000
    assert usage["user_limit"] == 25000
    assert usage["global_limit"] == 190000
    assert usage["emergency_reserve"] == 10000
    assert usage["date"]


def test_get_usage_handles_missing_rows_as_zero(monkeypatch):
    cursor, _ = use_db(monkeypatch)
    cursor._select_rows = [None, None]  # no rows yet today
    usage = token_ledger.get_usage("user@example.com")
    assert usage["user_tokens"] == 0
    assert usage["global_tokens"] == 0


def test_get_usage_normalizes_email_to_lowercase(monkeypatch):
    cursor, _ = use_db(monkeypatch)
    token_ledger.get_usage("User@Example.COM")
    assert cursor.executed[0][1][0] == "user@example.com"


# ---------------- check_budget ----------------

def test_check_budget_allows_request_that_fits_both_budgets(monkeypatch):
    use_db(monkeypatch, user_total=20000, global_total=100000)
    token_ledger.check_budget("user@example.com", estimated_tokens=4000)  # 24000 ≤ 25000


def test_check_budget_blocks_request_that_would_exceed_user_limit(monkeypatch):
    use_db(monkeypatch, user_total=20000, global_total=100000)
    with pytest.raises(token_ledger.QuotaExceededError) as exc:
        token_ledger.check_budget("user@example.com", estimated_tokens=6000)  # 26000 > 25000
    assert exc.value.scope == "user"


def test_check_budget_blocks_user_already_at_their_limit(monkeypatch):
    use_db(monkeypatch, user_total=25000, global_total=100000)
    with pytest.raises(token_ledger.QuotaExceededError) as exc:
        token_ledger.check_budget("user@example.com")
    assert exc.value.scope == "user"


def test_check_budget_blocks_request_that_would_drain_the_pool(monkeypatch):
    use_db(monkeypatch, user_total=0, global_total=188000)
    with pytest.raises(token_ledger.QuotaExceededError) as exc:
        token_ledger.check_budget("user@example.com", estimated_tokens=3000)  # 191000 > 190000
    assert exc.value.scope == "global"


def test_check_budget_blocks_everyone_when_pool_is_spent(monkeypatch):
    use_db(monkeypatch, user_total=0, global_total=190000)
    with pytest.raises(token_ledger.QuotaExceededError) as exc:
        token_ledger.check_budget("user@example.com")
    assert exc.value.scope == "global"


def test_check_budget_without_email_applies_only_global_limit(monkeypatch):
    use_db(monkeypatch, user_total=999999, global_total=0)
    token_ledger.check_budget("")  # anonymous → per-user check skipped


def test_check_budget_fails_open_when_db_is_down(monkeypatch):
    def boom():
        raise RuntimeError("db down")
    monkeypatch.setattr(token_ledger, "_connect", boom)
    token_ledger.check_budget("user@example.com", estimated_tokens=5000)  # must not raise


# ---------------- record_usage ----------------

FULL_USAGE = {"prompt_tokens": 200, "completion_tokens": 121, "total_tokens": 321}


def test_record_usage_updates_user_and_global_in_one_transaction(monkeypatch):
    cursor, conn = use_db(monkeypatch)
    token_ledger.record_usage("User@Example.com", FULL_USAGE)

    assert len(cursor.executed) == 2
    user_sql, user_params = cursor.executed[0]
    global_sql, global_params = cursor.executed[1]

    assert "INSERT INTO user_daily_usage" in user_sql
    assert "ON CONFLICT (user_email, usage_date)" in user_sql
    assert user_params[0] == "user@example.com"
    assert user_params[2:] == (200, 121, 321)

    assert "INSERT INTO global_daily_usage" in global_sql
    assert "ON CONFLICT (usage_date)" in global_sql
    assert global_params[1:] == (200, 121, 321)

    assert conn.commits == 1  # both rows in one transaction
    assert conn.closed


def test_record_usage_derives_total_when_provider_omits_it(monkeypatch):
    cursor, _ = use_db(monkeypatch)
    token_ledger.record_usage("u@e.com", {"prompt_tokens": 10, "completion_tokens": 5})
    assert cursor.executed[0][1][2:] == (10, 5, 15)


def test_record_usage_counts_anonymous_calls_against_global(monkeypatch):
    cursor, _ = use_db(monkeypatch)
    token_ledger.record_usage("", {"total_tokens": 10})
    assert cursor.executed[0][1][0] == token_ledger.UNKNOWN_USER


def test_record_usage_skips_missing_or_zero_usage(monkeypatch):
    connects = []
    monkeypatch.setattr(token_ledger, "_connect", lambda: connects.append(1))
    token_ledger.record_usage("user@example.com", None)
    token_ledger.record_usage("user@example.com", {"total_tokens": 0})
    token_ledger.record_usage("user@example.com", {})
    assert connects == []


def test_record_usage_swallows_db_errors(monkeypatch):
    def boom():
        raise RuntimeError("db down")
    monkeypatch.setattr(token_ledger, "_connect", boom)
    token_ledger.record_usage("user@example.com", FULL_USAGE)  # must not raise
