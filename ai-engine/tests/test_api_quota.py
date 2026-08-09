# Quota behavior of the non-Ignite LLM routes in api/api_server.py.
# Routes are called directly (same convention as the other tests — no HTTP
# server); global_chat / safe_generate are always mocked.

import asyncio
import json
import os

import pytest

os.environ.setdefault("GROQ_API_KEY", "test-key")

import api.api_server as srv
import core.llm as llm
import services.global_ai as ga
from services.token_ledger import QuotaExceededError


def run(coro):
    return asyncio.run(coro)


async def read_stream(response):
    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk.decode() if isinstance(chunk, bytes) else chunk)
    return "".join(chunks)


def deny_quota(*args, **kwargs):
    raise QuotaExceededError("user", "budget spent")


def body_of(json_response):
    return json.loads(json_response.body)


# ---------------- /global-ai ----------------

def test_global_ai_forwards_user_email(monkeypatch):
    seen = {}

    def fake_chat(prompt, file_text=None, has_file=False, model=None, user_email=""):
        seen["user_email"] = user_email
        return "answer"

    monkeypatch.setattr(ga, "global_chat", fake_chat)
    monkeypatch.setattr(srv, "save_chat", lambda *a: None)
    req = srv.AIRequest(question="hi", user_email="user@example.com")
    result = run(srv.global_ai(req))
    assert result == {"answer": "answer"}
    assert seen["user_email"] == "user@example.com"


def test_global_ai_quota_returns_429(monkeypatch):
    monkeypatch.setattr(ga, "global_chat", deny_quota)
    req = srv.AIRequest(question="hi", user_email="user@example.com")
    response = run(srv.global_ai(req))
    assert response.status_code == 429
    body = body_of(response)
    assert body["status"] == "quota"
    assert body["scope"] == "user"


# ---------------- /rag-ask ----------------

@pytest.fixture
def pdf_file(tmp_path):
    path = tmp_path / "doc.pdf"
    path.write_bytes(b"%PDF-1.4 fake")
    return str(path)


def test_rag_ask_quota_streams_inband_error(monkeypatch, pdf_file):
    monkeypatch.setattr(ga, "global_chat", deny_quota)
    with srv.rag_cache_lock:
        srv.rag_cache[pdf_file] = "document text"
    try:
        req = srv.RagRequest(question="hi", file_path=pdf_file,
                             user_email="user@example.com")
        response = run(srv.rag_ask(req))
        text = run(read_stream(response))
        assert text.startswith("ERROR: [quota:user]")
        assert "budget spent" in text
    finally:
        with srv.rag_cache_lock:
            srv.rag_cache.pop(pdf_file, None)
        with srv.doc_history_lock:
            srv.doc_chat_history.pop(pdf_file, None)


def test_rag_ask_forwards_user_email(monkeypatch, pdf_file):
    seen = {}

    def fake_chat(prompt, file_text=None, has_file=False, model=None, user_email=""):
        seen["user_email"] = user_email
        return "from the doc"

    monkeypatch.setattr(ga, "global_chat", fake_chat)
    with srv.rag_cache_lock:
        srv.rag_cache[pdf_file] = "document text"
    try:
        req = srv.RagRequest(question="hi", file_path=pdf_file,
                             user_email="user@example.com")
        response = run(srv.rag_ask(req))
        text = run(read_stream(response))
        assert text == "from the doc"
        assert seen["user_email"] == "user@example.com"
    finally:
        with srv.rag_cache_lock:
            srv.rag_cache.pop(pdf_file, None)
        with srv.doc_history_lock:
            srv.doc_chat_history.pop(pdf_file, None)


# ---------------- /code-analyze ----------------

def test_code_analyze_quota_returns_429(monkeypatch):
    monkeypatch.setattr(llm, "safe_generate", deny_quota)
    req = srv.CodeRequest(code="print(1)", language="python",
                          user_email="user@example.com")
    response = run(srv.code_analyze(req))
    assert response.status_code == 429
    body = body_of(response)
    assert body["status"] == "quota"
    assert body["scope"] == "user"


def test_code_analyze_forwards_user_email(monkeypatch):
    seen = {}

    def fake_generate(prompt, model=None, system=None, user_email=""):
        seen["user_email"] = user_email
        return json.dumps({
            "errors": "No errors found", "improvements": "- fine",
            "optimized_code": "print(1)", "before_time": "O(1)",
            "before_space": "O(1)", "time_complexity": "O(1)",
            "space_complexity": "O(1)", "data_structure": "None",
            "explanation": "trivial",
        })

    monkeypatch.setattr(llm, "safe_generate", fake_generate)
    req = srv.CodeRequest(code="print(1)", language="python",
                          user_email="user@example.com")
    result = run(srv.code_analyze(req))
    assert seen["user_email"] == "user@example.com"
    assert result["optimized_code"] == "print(1)"
