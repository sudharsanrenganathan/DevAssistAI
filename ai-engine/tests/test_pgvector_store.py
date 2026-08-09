"""Tests for rag.pgvector_store (psycopg2 mocked — no live DB)."""
import pytest

import rag.pgvector_store as pv


class FakeCursor:
    def __init__(self, fetchone_result=None, fetchall_result=None):
        self.fetchone_result = fetchone_result
        self.fetchall_result = fetchall_result or []
        self.executed = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql, params=None):
        self.executed.append((sql, params))

    def fetchone(self):
        return self.fetchone_result

    def fetchall(self):
        return self.fetchall_result


class FakeConn:
    def __init__(self, cursor):
        self._cursor = cursor
        self.committed = False
        self.closed = False

    def cursor(self):
        return self._cursor

    def commit(self):
        self.committed = True

    def close(self):
        self.closed = True


def _use_conn(monkeypatch, conn):
    monkeypatch.setattr(pv, "_connect", lambda: conn)


def test_vector_literal_format():
    assert pv._vector_literal([0.1, 0.2]) == "[0.10000000,0.20000000]"
    assert pv._vector_literal([1, 2, 3]).startswith("[1.00000000,")


def test_is_indexed_true_and_false(monkeypatch):
    conn = FakeConn(FakeCursor(fetchone_result=(1,)))
    _use_conn(monkeypatch, conn)
    assert pv.is_indexed("doc://x") is True
    assert conn.closed is True

    conn2 = FakeConn(FakeCursor(fetchone_result=None))
    _use_conn(monkeypatch, conn2)
    assert pv.is_indexed("doc://x") is False


def test_retrieve_reorders_by_chunk_index(monkeypatch):
    # DB returns similarity order; retrieve must return document order.
    cur = FakeCursor(fetchall_result=[(2, "c2"), (0, "c0"), (1, "c1")])
    conn = FakeConn(cur)
    _use_conn(monkeypatch, conn)
    out = pv.retrieve("doc://x", [0.1] * 384, k=3)
    assert out == ["c0", "c1", "c2"]
    # k and the vector literal are passed through
    sql, params = cur.executed[-1]
    assert params[0] == "doc://x"
    assert params[1].startswith("[") and params[1].endswith("]")
    assert params[2] == 3


def test_index_document_builds_rows_and_commits(monkeypatch):
    cur = FakeCursor()
    conn = FakeConn(cur)
    _use_conn(monkeypatch, conn)
    captured = {}

    def fake_execute_values(cursor, sql, rows, template=None):
        captured.update(sql=sql, rows=rows, template=template)

    monkeypatch.setattr(pv, "execute_values", fake_execute_values)

    n = pv.index_document("doc://x", ["a", "b"], [[0.1] * 3, [0.2] * 3], user_id="u1")
    assert n == 2
    assert conn.committed is True and conn.closed is True

    rows = captured["rows"]
    assert [r[2] for r in rows] == [0, 1]            # chunk_index sequence
    assert rows[0][0] == "doc://x" and rows[0][1] == "u1"
    assert rows[0][3] == "a"
    assert rows[0][4].startswith("[") and rows[0][4].endswith("]")
    assert "::vector" in captured["template"]


def test_index_empty_returns_zero(monkeypatch):
    _use_conn(monkeypatch, FakeConn(FakeCursor()))
    assert pv.index_document("doc://x", [], []) == 0


def test_index_length_mismatch_raises(monkeypatch):
    _use_conn(monkeypatch, FakeConn(FakeCursor()))
    with pytest.raises(ValueError):
        pv.index_document("doc://x", ["a", "b"], [[0.1] * 3])
