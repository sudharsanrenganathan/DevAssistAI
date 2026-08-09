"""Tests for core.embeddings — the Supabase gte-small edge client (mocked)."""
import pytest

import core.embeddings as emb


class _FakeResp:
    def __init__(self, payload, status=200):
        self._payload = payload
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self._payload


def test_embed_texts_calls_edge_and_returns_vectors(monkeypatch):
    monkeypatch.setenv("EMBEDDING_EDGE_URL", "https://x.supabase.co/functions/v1/embed")
    monkeypatch.setenv("EMBEDDING_EDGE_KEY", "test-key")
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured.update(url=url, headers=headers, json=json)
        return _FakeResp({"embeddings": [[0.1] * 384, [0.2] * 384]})

    monkeypatch.setattr(emb.requests, "post", fake_post)

    out = emb.embed_texts(["a", "b"])
    assert len(out) == 2 and len(out[0]) == 384
    assert captured["url"].endswith("/functions/v1/embed")
    assert captured["headers"]["Authorization"] == "Bearer test-key"
    assert captured["json"] == {"input": ["a", "b"]}


def test_embed_texts_empty_makes_no_call(monkeypatch):
    def boom(*a, **k):
        raise AssertionError("should not call the network for empty input")

    monkeypatch.setattr(emb.requests, "post", boom)
    assert emb.embed_texts([]) == []


def test_embed_query_returns_single_vector(monkeypatch):
    monkeypatch.setenv("EMBEDDING_EDGE_URL", "https://x.supabase.co/functions/v1/embed")
    monkeypatch.setenv("EMBEDDING_EDGE_KEY", "k")
    monkeypatch.setattr(emb.requests, "post",
                        lambda *a, **k: _FakeResp({"embeddings": [[0.5] * 384]}))
    vec = emb.embed_query("hello")
    assert len(vec) == 384 and vec[0] == 0.5


def test_url_derived_from_supabase_url(monkeypatch):
    monkeypatch.delenv("EMBEDDING_EDGE_URL", raising=False)
    monkeypatch.setenv("SUPABASE_URL", "https://proj.supabase.co")
    monkeypatch.setenv("EMBEDDING_EDGE_KEY", "k")
    captured = {}
    monkeypatch.setattr(
        emb.requests, "post",
        lambda url, **k: (captured.update(url=url), _FakeResp({"embeddings": [[0.0] * 384]}))[1],
    )
    emb.embed_texts(["x"])
    assert captured["url"] == "https://proj.supabase.co/functions/v1/embed"


def test_missing_config_raises(monkeypatch):
    monkeypatch.delenv("EMBEDDING_EDGE_URL", raising=False)
    monkeypatch.delenv("SUPABASE_URL", raising=False)
    monkeypatch.setenv("EMBEDDING_EDGE_KEY", "k")
    with pytest.raises(RuntimeError):
        emb.embed_texts(["x"])


def test_shape_mismatch_raises(monkeypatch):
    monkeypatch.setenv("EMBEDDING_EDGE_URL", "https://x/embed")
    monkeypatch.setenv("EMBEDDING_EDGE_KEY", "k")
    # one vector returned for two inputs -> error
    monkeypatch.setattr(emb.requests, "post",
                        lambda *a, **k: _FakeResp({"embeddings": [[0.1] * 384]}))
    with pytest.raises(RuntimeError):
        emb.embed_texts(["a", "b"])
