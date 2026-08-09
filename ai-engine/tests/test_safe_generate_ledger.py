# Tests for core/llm.py safe_generate — the single choke point through which
# every non-Ignite feature reaches Groq. The Groq client and the token ledger
# are always mocked; no API calls, no database.

import os

import pytest

os.environ.setdefault("GROQ_API_KEY", "test-key")  # Groq() refuses a None key

import core.llm as llm
from services.token_ledger import QuotaExceededError


class FakeUsage:
    def __init__(self, total_tokens, prompt_tokens=0, completion_tokens=0):
        self.total_tokens = total_tokens
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens


class FakeMessage:
    def __init__(self, content):
        self.content = content


class FakeChoice:
    def __init__(self, content):
        self.message = FakeMessage(content)


class FakeResponse:
    def __init__(self, content, total_tokens=None):
        self.choices = [FakeChoice(content)]
        self.usage = FakeUsage(total_tokens) if total_tokens is not None else None


class FakeCompletions:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.response


def use_client(monkeypatch, response=None, error=None):
    completions = FakeCompletions(response, error)

    class FakeClient:
        class chat:
            pass

    FakeClient.chat.completions = completions
    monkeypatch.setattr(llm, "client", FakeClient)
    return completions


@pytest.fixture(autouse=True)
def ledger(monkeypatch):
    """Stub the ledger: budgets always allow, recorded calls are captured."""
    calls = {"checked": [], "recorded": []}
    monkeypatch.setattr(llm, "check_budget",
                        lambda email, estimated_tokens=0:
                        calls["checked"].append((email, estimated_tokens)))
    monkeypatch.setattr(llm, "record_usage",
                        lambda email, usage: calls["recorded"].append((email, usage)))
    return calls


def test_checks_budget_with_estimate_and_records_full_usage(monkeypatch, ledger):
    use_client(monkeypatch, FakeResponse("hello", total_tokens=345))
    answer = llm.safe_generate("hi", user_email="user@example.com")
    assert answer == "hello"
    email, estimate = ledger["checked"][0]
    assert email == "user@example.com"
    # estimate = prompt chars // 4 + reserved completion tokens
    assert estimate == len("hi") // 4 + llm.MAX_COMPLETION_TOKENS
    assert ledger["recorded"] == [("user@example.com", {
        "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 345})]


def test_quota_error_prevents_any_llm_call(monkeypatch, ledger):
    completions = use_client(monkeypatch, FakeResponse("never"))

    def deny(email, estimated_tokens=0):
        raise QuotaExceededError("user", "budget spent")
    monkeypatch.setattr(llm, "check_budget", deny)

    with pytest.raises(QuotaExceededError):
        llm.safe_generate("hi", user_email="user@example.com")
    assert completions.calls == []
    assert ledger["recorded"] == []


def test_missing_usage_is_not_recorded(monkeypatch, ledger):
    use_client(monkeypatch, FakeResponse("hello", total_tokens=None))
    assert llm.safe_generate("hi", user_email="u@e.com") == "hello"
    assert ledger["recorded"] == []


def test_provider_failure_returns_fallback_and_records_nothing(monkeypatch, ledger):
    monkeypatch.setattr(llm.time, "sleep", lambda s: None)
    use_client(monkeypatch, error=RuntimeError("groq down"))
    answer = llm.safe_generate("hi", user_email="u@e.com")
    assert answer == "⚠️ AI temporarily unavailable"
    assert ledger["recorded"] == []


def test_anonymous_email_defaults_to_empty_string(monkeypatch, ledger):
    use_client(monkeypatch, FakeResponse("hello", total_tokens=10))
    llm.safe_generate("hi")
    assert ledger["checked"][0][0] == ""
    assert ledger["recorded"] == [("", {
        "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 10})]
