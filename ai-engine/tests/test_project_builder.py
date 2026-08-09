# Tests for services/project_builder.py — the LLM provider is always mocked.

import json

import pytest

import services.project_builder as pb
from core.llm_providers import ProviderError
from services.token_ledger import QuotaExceededError

FAKE_USAGE = {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}


class FakeProvider:
    """Provider double: queued non-stream responses, fixed stream chunks,
    FAKE_USAGE reported through usage_callback like real providers do."""

    def __init__(self, responses=None, stream_chunks=None):
        self.responses = list(responses or [])
        self.stream_chunks = list(stream_chunks or [])
        self.calls = []

    def generate(self, messages, stream=False, max_tokens=0, temperature=0.0,
                 usage_callback=None):
        self.calls.append({"messages": messages, "stream": stream})
        if stream:
            return self._stream(usage_callback)
        if usage_callback:
            usage_callback(FAKE_USAGE)
        return self.responses.pop(0)

    def _stream(self, usage_callback):
        yield from self.stream_chunks
        if usage_callback:
            usage_callback(FAKE_USAGE)


def use_provider(monkeypatch, provider):
    monkeypatch.setattr(pb, "get_provider", lambda: provider)


@pytest.fixture(autouse=True)
def ledger(monkeypatch):
    """Stub the token ledger for every test: budgets always allow, recorded
    (email, usage) pairs are captured for assertions. No database is touched."""
    recorded = []
    monkeypatch.setattr(pb.token_ledger, "check_budget",
                        lambda email, estimated_tokens=0: None)
    monkeypatch.setattr(pb.token_ledger, "record_usage",
                        lambda email, usage: recorded.append((email, usage)))
    return recorded


def deny_budget(monkeypatch, scope="user"):
    def deny(email, estimated_tokens=0):
        raise QuotaExceededError(scope, "daily budget spent")
    monkeypatch.setattr(pb.token_ledger, "check_budget", deny)


VALID_PLAN = {
    "status": "ok",
    "project": {"name": "Arjun Portfolio", "slug": "arjun-portfolio",
                "summary": "Photographer portfolio."},
    "files": [
        {"path": "index.html", "description": "main page"},
        {"path": "css/style.css", "description": "styles"},
        {"path": "js/main.js", "description": "interactions"},
    ],
}

DETAILED_PROMPT = ("Build a modern portfolio website for a photographer named Arjun "
                   "with a gallery grid and a contact form")


# ---------------- create_plan ----------------

def test_vague_prompt_short_circuits_without_llm_call(monkeypatch):
    exploding = FakeProvider()
    use_provider(monkeypatch, exploding)
    result = pb.create_plan("a website")
    assert result["status"] == "needs_detail"
    assert result["message"]
    assert exploding.calls == []


def test_create_plan_parses_json_wrapped_in_think_and_fences(monkeypatch):
    raw = ("<think>planning the structure...</think>\n"
           "```json\n" + json.dumps(VALID_PLAN) + "\n```")
    use_provider(monkeypatch, FakeProvider(responses=[raw]))
    plan = pb.create_plan(DETAILED_PROMPT)
    assert plan["status"] == "ok"
    assert plan["project"]["slug"] == "arjun-portfolio"
    assert [f["path"] for f in plan["files"]] == ["index.html", "css/style.css", "js/main.js"]


def test_create_plan_retries_once_on_malformed_json(monkeypatch):
    provider = FakeProvider(responses=["sorry, no JSON here", json.dumps(VALID_PLAN)])
    use_provider(monkeypatch, provider)
    plan = pb.create_plan(DETAILED_PROMPT)
    assert plan["status"] == "ok"
    assert len(provider.calls) == 2


def test_create_plan_fails_cleanly_when_json_never_valid(monkeypatch):
    use_provider(monkeypatch, FakeProvider(responses=["garbage", "more garbage"]))
    with pytest.raises(ProviderError):
        pb.create_plan(DETAILED_PROMPT)


def test_create_plan_filters_unsafe_paths(monkeypatch):
    dirty = {
        "status": "ok",
        "project": {"name": "X", "slug": "x", "summary": ""},
        "files": [
            {"path": "../evil.js", "description": "traversal"},
            {"path": "/etc/passwd.txt", "description": "absolute"},
            {"path": "app.exe", "description": "bad extension"},
            {"path": "index.html", "description": "legit"},
        ],
    }
    use_provider(monkeypatch, FakeProvider(responses=[json.dumps(dirty)]))
    plan = pb.create_plan(DETAILED_PROMPT)
    assert [f["path"] for f in plan["files"]] == ["index.html"]


def test_create_plan_passes_needs_detail_through(monkeypatch):
    raw = json.dumps({"status": "needs_detail", "message": "What is the site about?"})
    use_provider(monkeypatch, FakeProvider(responses=[raw]))
    result = pb.create_plan(DETAILED_PROMPT)
    assert result == {"status": "needs_detail", "message": "What is the site about?"}


# ---------------- generate_file_stream ----------------

def collect(stream):
    return "".join(stream)


def test_generate_file_stream_passes_plain_content_through(monkeypatch):
    chunks = ["<!DOCTYPE html>\n", "<html>", "</html>"]
    use_provider(monkeypatch, FakeProvider(stream_chunks=chunks))
    out = collect(pb.generate_file_stream(
        DETAILED_PROMPT, "summary", "index.html", "main page", ["index.html"], {}))
    assert out == "<!DOCTYPE html>\n<html></html>"


def test_generate_file_stream_strips_fences(monkeypatch):
    chunks = ["```html\n", "<html>", "</html>", "\n```"]
    use_provider(monkeypatch, FakeProvider(stream_chunks=chunks))
    out = collect(pb.generate_file_stream(
        DETAILED_PROMPT, "", "index.html", "", [], {}))
    assert out.strip() == "<html></html>"


def test_generate_file_stream_strips_think_block_split_across_chunks(monkeypatch):
    chunks = ["<thi", "nk>let me reason", " about it</think>\n", "body { margin: 0; }"]
    use_provider(monkeypatch, FakeProvider(stream_chunks=chunks))
    out = collect(pb.generate_file_stream(
        DETAILED_PROMPT, "", "css/style.css", "", [], {}))
    assert out == "body { margin: 0; }"


def test_generate_file_stream_rejects_traversal_paths(monkeypatch):
    use_provider(monkeypatch, FakeProvider(stream_chunks=["x"]))
    with pytest.raises(ValueError):
        pb.generate_file_stream(DETAILED_PROMPT, "", "../evil.js", "", [], {})


def test_budgets_fit_groq_free_tier_tpm():
    # Groq on-demand counts prompt tokens (≈ chars/4) plus reserved max_tokens
    # against an 8k TPM cap; ~1000 tokens of allowance covers the system and
    # user prompt scaffolding around the file context.
    assert pb.MAX_CONTEXT_CHARS / 4 + pb.MAX_FILE_TOKENS + 1000 <= 8000


def test_generate_file_context_puts_index_html_first_and_caps_length(monkeypatch):
    existing = {
        "css/style.css": "c" * 30000,
        "index.html": "<html></html>",
    }
    block = pb._existing_files_block(existing)
    assert block.index("index.html") < block.index("css/style.css")
    assert len(block) <= pb.MAX_CONTEXT_CHARS + 500  # headers/markers allowance


def test_generate_file_prompt_includes_project_structure(monkeypatch):
    provider = FakeProvider(stream_chunks=["ok"])
    use_provider(monkeypatch, provider)
    collect(pb.generate_file_stream(
        DETAILED_PROMPT, "a portfolio", "js/main.js", "interactions",
        ["index.html", "css/style.css", "js/main.js"], {"index.html": "<html></html>"}))
    user_message = provider.calls[0]["messages"][1]["content"]
    assert "js/main.js" in user_message
    assert "index.html" in user_message
    assert DETAILED_PROMPT in user_message


# ---------------- apply_edit ----------------

def test_apply_edit_returns_changed_files(monkeypatch):
    raw = json.dumps({
        "message": "Made the hero full-screen.",
        "changed_files": {"css/style.css": ".hero { height: 100vh; }"},
    })
    use_provider(monkeypatch, FakeProvider(responses=[raw]))
    result = pb.apply_edit("make the hero full screen", {"css/style.css": ".hero {}"})
    assert result["status"] == "ok"
    assert result["message"] == "Made the hero full-screen."
    assert result["changed_files"] == {"css/style.css": ".hero { height: 100vh; }"}


def test_apply_edit_filters_unsafe_paths(monkeypatch):
    raw = json.dumps({
        "message": "done",
        "changed_files": {"../evil.js": "bad", "js/app.js": "console.log(1);"},
    })
    use_provider(monkeypatch, FakeProvider(responses=[raw]))
    result = pb.apply_edit("tweak the js", {"js/app.js": "console.log(0);"})
    assert list(result["changed_files"]) == ["js/app.js"]


def test_apply_edit_rejects_empty_prompt(monkeypatch):
    use_provider(monkeypatch, FakeProvider())
    with pytest.raises(ValueError):
        pb.apply_edit("   ", {})


def test_apply_edit_fails_cleanly_on_garbage_responses(monkeypatch):
    use_provider(monkeypatch, FakeProvider(responses=["garbage", "still garbage"]))
    with pytest.raises(ProviderError):
        pb.apply_edit("change the theme", {"index.html": "<html></html>"})


# ---------------- token budgets ----------------

def test_create_plan_records_reported_usage(monkeypatch, ledger):
    use_provider(monkeypatch, FakeProvider(responses=[json.dumps(VALID_PLAN)]))
    pb.create_plan(DETAILED_PROMPT, user_email="user@example.com")
    assert ledger == [("user@example.com", FAKE_USAGE)]


def test_create_plan_blocked_when_quota_spent_without_calling_provider(monkeypatch, ledger):
    provider = FakeProvider(responses=[json.dumps(VALID_PLAN)])
    use_provider(monkeypatch, provider)
    deny_budget(monkeypatch)
    with pytest.raises(QuotaExceededError):
        pb.create_plan(DETAILED_PROMPT, user_email="user@example.com")
    assert provider.calls == []
    assert ledger == []


def test_vague_prompt_answered_before_budget_check(monkeypatch):
    # A needs_detail answer costs no tokens, so it must work even over budget.
    use_provider(monkeypatch, FakeProvider())
    deny_budget(monkeypatch)
    assert pb.create_plan("a website", user_email="user@example.com")["status"] == "needs_detail"


def test_generate_file_stream_records_usage_after_stream_ends(monkeypatch, ledger):
    use_provider(monkeypatch, FakeProvider(stream_chunks=["<html>", "</html>"]))
    out = collect(pb.generate_file_stream(
        DETAILED_PROMPT, "", "index.html", "", [], {}, user_email="user@example.com"))
    assert out == "<html></html>"
    assert ledger == [("user@example.com", FAKE_USAGE)]


def test_generate_file_stream_blocked_when_quota_spent(monkeypatch, ledger):
    provider = FakeProvider(stream_chunks=["x"])
    use_provider(monkeypatch, provider)
    deny_budget(monkeypatch, scope="global")
    with pytest.raises(QuotaExceededError) as exc:
        pb.generate_file_stream(DETAILED_PROMPT, "", "index.html", "", [], {},
                                user_email="user@example.com")
    assert exc.value.scope == "global"
    assert provider.calls == []


def test_apply_edit_records_reported_usage(monkeypatch, ledger):
    raw = json.dumps({"message": "done",
                      "changed_files": {"js/app.js": "console.log(1);"}})
    use_provider(monkeypatch, FakeProvider(responses=[raw]))
    pb.apply_edit("tweak the js", {"js/app.js": "console.log(0);"},
                  user_email="user@example.com")
    assert ledger == [("user@example.com", FAKE_USAGE)]


def test_apply_edit_blocked_when_quota_spent(monkeypatch, ledger):
    provider = FakeProvider(responses=["{}"])
    use_provider(monkeypatch, provider)
    deny_budget(monkeypatch)
    with pytest.raises(QuotaExceededError):
        pb.apply_edit("tweak the js", {}, user_email="user@example.com")
    assert provider.calls == []
