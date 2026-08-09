# services/project_builder.py — business logic for Ignite (the AI project builder).
#
# Flow (all provider-agnostic via core/llm_providers.get_provider):
#   1. create_plan(prompt)          -> strict-JSON project plan, or a friendly
#                                      "needs_detail" answer for vague prompts
#   2. generate_file_stream(...)    -> streams one file's raw content, called
#                                      once per planned file, sequentially

import itertools
import json
import os
import re
from typing import Callable, Iterator, Optional

from core.llm_providers import ProviderError, get_provider
from services import token_ledger

FEATURE_NAME = "Ignite"

MIN_PROMPT_CHARS = 25
MIN_PROMPT_WORDS = 4
MAX_FILES = 15
# Budgets must fit the smallest supported provider tier: Groq on-demand counts
# prompt tokens (≈ chars/4) PLUS the reserved max_tokens against an 8k
# tokens-per-minute cap, so context + output together must stay under it or
# late files in a project 413 ("Requested 8127, Limit 8000").
MAX_CONTEXT_CHARS = int(os.getenv("PROJECT_BUILDER_CONTEXT_CHARS", "10000"))
MAX_FILE_TOKENS = int(os.getenv("PROJECT_BUILDER_MAX_FILE_TOKENS", "4096"))
_ALLOWED_EXTENSIONS = (".html", ".css", ".js", ".md", ".svg", ".json", ".txt")

DEFAULT_DETAIL_MESSAGE = (
    "Could you tell me a bit more about what you want to build? For example: "
    "what is the site for, what sections or pages should it have, and any "
    "preferred colors or theme? The more detail you give, the better the result."
)

PLAN_SYSTEM_PROMPT = f"""You are {FEATURE_NAME}, an expert frontend architect.
The user will describe a frontend-only project (plain HTML, CSS and JavaScript —
no build tools, no frameworks, no backend).

Respond with STRICT JSON only — no markdown fences, no commentary:
{{
  "status": "ok",
  "project": {{"name": "Display Name", "slug": "kebab-case-slug", "summary": "one sentence"}},
  "files": [
    {{"path": "index.html", "description": "what this file contains"}}
  ]
}}

Rules:
- 4 to 12 files. index.html first, then css/ files, then js/ files, README.md last.
- Only .html, .css, .js, .md and .svg files. Relative paths only, no folders deeper
  than one level (e.g. css/style.css is fine).
- Every file listed must be genuinely needed by the project.
- If the user's prompt is too vague to build something good, respond instead with:
  {{"status": "needs_detail", "message": "a friendly question asking for the missing specifics"}}"""

FILE_SYSTEM_PROMPT = f"""You are {FEATURE_NAME}, an expert frontend developer writing
one file of a multi-file HTML/CSS/JS project. Output ONLY the raw file content —
no markdown fences, no explanations, no file path header. The code must be complete,
valid and consistent with the other project files shown to you."""

EDIT_SYSTEM_PROMPT = f"""You are {FEATURE_NAME}, an expert frontend developer modifying an
existing HTML/CSS/JS project. The user asks for a change. Respond with STRICT JSON only —
no markdown fences, no commentary:
{{
  "message": "one friendly sentence describing what you changed",
  "changed_files": {{"path/from/project": "the COMPLETE new file content"}}
}}

Rules:
- Include ONLY the files you changed or added, but each with its FULL new content.
- Do not rename or delete files. Keep everything else consistent.
- Only .html, .css, .js, .md and .svg files with safe relative paths."""


def _is_safe_path(path: str) -> bool:
    if not path or len(path) > 120:
        return False
    if path.startswith(("/", "\\")) or ".." in path or ":" in path:
        return False
    if not path.lower().endswith(_ALLOWED_EXTENSIONS):
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9_\-./]+", path))


def _extract_json(raw: str) -> dict:
    """Pull the first JSON object out of a model response, tolerating <think>
    blocks (reasoning models) and markdown fences around the JSON."""
    text = re.sub(r"<think>.*?</think>", "", raw or "", flags=re.DOTALL).strip()
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no JSON object found in model response")
    return json.loads(text[start:end + 1])


def _validate_plan(data: dict) -> dict:
    if data.get("status") == "needs_detail":
        return {"status": "needs_detail",
                "message": str(data.get("message") or DEFAULT_DETAIL_MESSAGE)}

    project = data.get("project") or {}
    files: list = []
    seen: set = set()
    for entry in data.get("files") or []:
        if not isinstance(entry, dict):
            continue
        path = str(entry.get("path", "")).strip().replace("\\", "/")
        if not _is_safe_path(path) or path.lower() in seen:
            continue
        seen.add(path.lower())
        files.append({"path": path,
                      "description": str(entry.get("description", "")).strip()[:300]})
        if len(files) >= MAX_FILES:
            break
    if not files:
        raise ValueError("plan contained no usable files")

    name = str(project.get("name") or "Generated Project").strip()[:80]
    slug = re.sub(r"[^a-z0-9-]+", "-", str(project.get("slug") or name).lower()).strip("-")
    summary = str(project.get("summary") or "").strip()[:400]
    return {
        "status": "ok",
        "project": {"name": name, "slug": (slug or "project")[:60], "summary": summary},
        "files": files,
    }


def _usage_recorder(user_email: str) -> Callable[[Optional[dict]], None]:
    """Ledger hook passed to providers — writes each call's reported usage."""
    return lambda usage: token_ledger.record_usage(user_email, usage)


def _check_budget_for(messages: list, max_tokens: int, user_email: str) -> None:
    """Pre-flight quota check sized to this exact request (prompt + reserved
    completion tokens), so a request that would exceed a budget is rejected
    before the provider is called."""
    prompt_chars = sum(len(m.get("content") or "") for m in messages)
    token_ledger.check_budget(
        user_email, token_ledger.estimate_tokens(prompt_chars, max_tokens))


def create_plan(prompt: str, user_email: str = "") -> dict:
    """Ask the LLM for a project plan. Returns a validated plan dict, a
    needs_detail dict for vague prompts, or raises QuotaExceededError/
    ProviderError/ProviderBusyError."""
    cleaned = (prompt or "").strip()
    if len(cleaned) < MIN_PROMPT_CHARS or len(cleaned.split()) < MIN_PROMPT_WORDS:
        return {"status": "needs_detail", "message": DEFAULT_DETAIL_MESSAGE}

    provider = get_provider()
    messages = [
        {"role": "system", "content": PLAN_SYSTEM_PROMPT},
        {"role": "user", "content": cleaned},
    ]
    _check_budget_for(messages, 2048, user_email)
    last_error: Exception = ValueError("plan generation was never attempted")
    for _ in range(2):  # one retry on malformed JSON
        raw = provider.generate(messages, stream=False, max_tokens=2048, temperature=0.2,
                                usage_callback=_usage_recorder(user_email))
        try:
            return _validate_plan(_extract_json(raw))
        except ValueError as e:
            last_error = e
    raise ProviderError(f"The model did not return a usable plan: {last_error}")


def _existing_files_block(existing_files: dict) -> str:
    """Serialize already-written files as context, index.html first, capped to
    MAX_CONTEXT_CHARS so the request stays within model context limits."""
    ordered = sorted(existing_files.items(), key=lambda kv: (kv[0] != "index.html", kv[0]))
    parts: list = []
    used = 0
    for path, content in ordered:
        remaining = MAX_CONTEXT_CHARS - used
        if remaining <= 0:
            parts.append(f"--- {path} (omitted for length) ---")
            continue
        content = str(content)
        snippet = content[:remaining]
        used += len(snippet)
        marker = " (truncated)" if len(snippet) < len(content) else ""
        parts.append(f"--- {path}{marker} ---\n{snippet}")
    return "\n\n".join(parts)


def _build_file_prompt(prompt: str, plan_summary: str, file_path: str,
                       file_description: str, files_list: list,
                       existing_files: dict) -> str:
    files_overview = "\n".join(f"- {p}" for p in files_list) if files_list else "(not provided)"
    context = _existing_files_block(existing_files) if existing_files else "(none yet)"
    return (
        f"Project request:\n{prompt}\n\n"
        f"Plan summary: {plan_summary or '(none)'}\n\n"
        f"All files in this project:\n{files_overview}\n\n"
        f"Files already written:\n{context}\n\n"
        f"Now write the complete content of `{file_path}` — "
        f"{file_description or 'as planned'}.\n"
        f"Remember: output ONLY the raw file content."
    )


def _clean_stream(chunks: Iterator[str]) -> Iterator[str]:
    """Strip a leading <think> block and a surrounding markdown fence from a token
    stream, yielding only real file content. A small tail is held back so a closing
    fence can be removed once the stream ends."""
    tail_size = 16
    buffer = ""
    prologue_done = False
    opened_fence = False
    for chunk in chunks:
        buffer += chunk
        if not prologue_done:
            text = buffer.lstrip()
            if not text:
                continue
            if len(text) < 7 and "<think>".startswith(text):
                continue  # might be a partial "<think>" — keep buffering
            if text.startswith("<think>"):
                end = text.find("</think>")
                if end == -1:
                    continue  # think block not closed yet
                buffer = text[end + len("</think>"):]
                text = buffer.lstrip()
                if not text:
                    buffer = ""
                    continue
            if text.startswith("```"):
                if "\n" not in text:
                    buffer = text
                    continue  # wait for the full ```lang line
                buffer = text.split("\n", 1)[1]
                opened_fence = True
            elif text.startswith("`") and "\n" not in text and len(text) < 4:
                buffer = text
                continue  # could still become a fence
            else:
                buffer = text
            prologue_done = True
        if len(buffer) > tail_size:
            yield buffer[:-tail_size]
            buffer = buffer[-tail_size:]
    if opened_fence:
        stripped = buffer.rstrip()
        if stripped.endswith("```"):
            buffer = stripped[:-3].rstrip() + "\n"
    if buffer:
        yield buffer


def generate_file_stream(prompt: str, plan_summary: str, file_path: str,
                         file_description: str, files_list: Optional[list],
                         existing_files: Optional[dict],
                         user_email: str = "") -> Iterator[str]:
    """Stream one file's content. Raises ValueError for unsafe paths and
    QuotaExceededError/ProviderError/ProviderBusyError before the first chunk,
    so callers can still return a proper HTTP status."""
    path = (file_path or "").strip().replace("\\", "/")
    if not _is_safe_path(path):
        raise ValueError(f"unsafe or unsupported file path: {file_path!r}")

    provider = get_provider()
    messages = [
        {"role": "system", "content": FILE_SYSTEM_PROMPT},
        {"role": "user", "content": _build_file_prompt(
            prompt, plan_summary, path, file_description,
            files_list or [], existing_files or {})},
    ]
    _check_budget_for(messages, MAX_FILE_TOKENS, user_email)
    raw = provider.generate(messages, stream=True,
                            max_tokens=MAX_FILE_TOKENS, temperature=0.3,
                            usage_callback=_usage_recorder(user_email))
    cleaned = _clean_stream(raw)
    try:
        first = next(cleaned)  # provider/quota errors surface here, pre-stream
    except StopIteration:
        return iter([""])
    return itertools.chain([first], cleaned)


def _validate_edit(data: dict) -> dict:
    changed: dict = {}
    for path, content in (data.get("changed_files") or {}).items():
        clean_path = str(path).strip().replace("\\", "/")
        if _is_safe_path(clean_path) and isinstance(content, str) and content.strip():
            changed[clean_path] = content
    if not changed:
        raise ValueError("edit response contained no usable files")
    return {
        "status": "ok",
        "message": str(data.get("message") or "Changes applied.").strip()[:400],
        "changed_files": changed,
    }


def apply_edit(prompt: str, files: Optional[dict], user_email: str = "") -> dict:
    """Apply a follow-up change request to an existing generated project.
    Returns {status, message, changed_files} with full new content per file."""
    cleaned = (prompt or "").strip()
    if not cleaned:
        raise ValueError("the change request is empty")

    provider = get_provider()
    user_content = (
        f"Change request:\n{cleaned}\n\n"
        f"Current project files:\n{_existing_files_block(files or {}) or '(none provided)'}"
    )
    messages = [
        {"role": "system", "content": EDIT_SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]
    _check_budget_for(messages, MAX_FILE_TOKENS, user_email)
    last_error: Exception = ValueError("edit was never attempted")
    for _ in range(2):  # one retry on malformed JSON
        raw = provider.generate(messages, stream=False,
                                max_tokens=MAX_FILE_TOKENS, temperature=0.3,
                                usage_callback=_usage_recorder(user_email))
        try:
            return _validate_edit(_extract_json(raw))
        except ValueError as e:
            last_error = e
    raise ProviderError(f"The model did not return a usable edit: {last_error}")
