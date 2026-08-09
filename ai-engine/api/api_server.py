from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from threading import Lock
import os
# Prevent PyTorch from spawning many threads and causing out of memory on Render Free Tier
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"
os.environ["OPENBLAS_NUM_THREADS"] = "1"
import gc

# ABSOLUTE MINIMAL STARTUP - ALL LIBRARIES LOADED ON-DEMAND

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

# ========================= CACHE (THREAD-SAFE) =========================
rag_cache = {}
rag_cache_lock = Lock()
doc_chat_history = {}
doc_history_lock = Lock()
global_chat_history = {}
global_history_lock = Lock()

app = FastAPI()
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

UPLOAD_DIR = "uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)

# ========================= RAG (Secret AI) CONFIG =========================
# pgvector semantic retrieval with a full-text fallback. Set RAG_MODE=fulltext
# to disable retrieval entirely (instant rollback, no redeploy logic change).
RAG_MODE = os.getenv("RAG_MODE", "pgvector").lower()
RAG_TOP_K = int(os.getenv("RAG_TOP_K", "6"))
RAG_CONTEXT_CHARS = int(os.getenv("RAG_CONTEXT_CHARS", "8000"))

@app.get("/")
async def root():
    return {"status": "online", "service": "DevAssist AI Engine v2"}

@app.get("/health")
async def health():
    return {"status": "healthy"}

# Models moved inside routes or defined minimally
from pydantic import BaseModel
from typing import Optional, Union

# Lightweight import (os/datetime only) — every LLM route needs the quota types.
from services.token_ledger import QuotaExceededError


def quota_response(e: QuotaExceededError) -> JSONResponse:
    # Same 429 wire format as "busy", but status "quota" + scope so clients can
    # tell "your daily tokens are used" from "the app hit its daily capacity".
    return JSONResponse(status_code=429, content={
        "status": "quota", "scope": e.scope, "message": str(e)})

class AIRequest(BaseModel):
    question: str
    file_text: Optional[str] = None
    has_file: Optional[bool] = False
    model: Optional[str] = None
    session_id: Optional[str] = None
    user_email: Optional[str] = ""

class RagRequest(BaseModel):
    question: str
    file_path: str
    model: Optional[str] = None
    session_id: Optional[Union[str, int]] = None
    user_email: Optional[str] = ""

class CodeRequest(BaseModel):
    code: str
    language: str
    model: Optional[str] = None
    user_email: Optional[str] = ""

# ========================= DB HELPERS =========================
def get_db():
    import psycopg2
    return psycopg2.connect(os.getenv("DATABASE_URL"))

def save_chat(session_id, question, answer):
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("INSERT INTO global_messages (chat_id, role, content) VALUES (%s, %s, %s)",
                    (session_id, "user", question))
        cur.execute("INSERT INTO global_messages (chat_id, role, content) VALUES (%s, %s, %s)",
                    (session_id, "ai", answer))
        conn.commit()
        cur.close()
        conn.close()
    except Exception as e:
        print(f"⚠ save_chat error: {e}")

# ========================= PATTERN DETECTION =========================
def detect_pattern(code: str):
    code_lower = code.lower()
    return {
        "nested_loops": code_lower.count("for") >= 2 or code_lower.count("while") >= 2,
        "duplicate_problem": "==" in code and "for" in code_lower,
        "search_problem": "contains" in code_lower or "indexof" in code_lower,
        "sorting_inside_loop": "sort" in code_lower and "for" in code_lower
    }

def build_optimization_hint(patterns):
    hints = []
    if patterns["nested_loops"] and patterns["duplicate_problem"]:
        hints.append("Duplicate detection: replace nested loops with HashSet → O(n).")
    elif patterns["nested_loops"]:
        hints.append("Nested loops: try single pass with HashMap or Set.")
    if patterns["search_problem"]:
        hints.append("Search detected: use HashMap for O(1) lookup.")
    if patterns["sorting_inside_loop"]:
        hints.append("Sorting inside loop: move sort outside or optimize.")
    return "\n".join(hints)

# ========================= GLOBAL AI =========================
@app.post("/global-ai")
async def global_ai(request: AIRequest):
    session_key = str(request.session_id) if request.session_id else "default"
    print(f"🌍 Global AI | Session: {session_key} | Q: {request.question}")

    # In-memory per-chat history (thread-safe)
    with global_history_lock:
        if session_key not in global_chat_history:
            global_chat_history[session_key] = []
            # ✅ PERSIST FIX: Load existing messages from DB via Spring Boot API
            if request.session_id:
                try:
                    import urllib.request, json as _json
                    db_url = f"{BACKEND_URL}/api/global-chat/{request.session_id}"
                    req = urllib.request.Request(db_url)
                    with urllib.request.urlopen(req, timeout=3) as resp:
                        db_msgs = _json.loads(resp.read().decode())
                    i = 0
                    while i < len(db_msgs) - 1:
                        if db_msgs[i].get("role") == "user" and db_msgs[i+1].get("role") == "ai":
                            global_chat_history[session_key].append({
                                "q": db_msgs[i]["content"],
                                "a": db_msgs[i+1]["content"]
                            })
                            i += 2
                        else:
                            i += 1
                    print(f"📥 Loaded {len(global_chat_history[session_key])} global history pairs from DB for session {session_key}")
                except Exception as e:
                    print(f"⚠ Could not load global history from DB: {e}")
        history = global_chat_history[session_key]
    
    print(f"📜 Global history length: {len(history)}")

    # Build conversation history string
    history_text = ""
    if history:
        history_lines = []
        for h in history[-8:]:
            history_lines.append(f"User: {h['q']}")
            ans = h['a'][:500] if len(h['a']) > 500 else h['a']
            history_lines.append(f"Assistant: {ans}")
        history_text = "\n".join(history_lines)

    # Build the full prompt with history
    if history_text:
        full_prompt = f"""<conversation_history>
{history_text}
</conversation_history>

User: {request.question}"""
    else:
        full_prompt = request.question

    try:
        from services.global_ai import global_chat
        answer = global_chat(
            full_prompt,
            request.file_text,
            request.has_file,
            request.model,
            user_email=request.user_email or ""
        )

        # Save to in-memory history (thread-safe)
        with global_history_lock:
            history.append({"q": request.question, "a": answer})
        print(f"✅ Global history saved. New length: {len(history)}")

        # Also save to DB for persistence
        try:
            session_int = int(request.session_id) if request.session_id else 1
            save_chat(session_int, request.question, answer)
        except Exception as e:
            print(f"⚠ DB save failed (non-critical): {e}")

        return {"answer": answer}

    except QuotaExceededError as e:
        return quota_response(e)
    except Exception as e:
        error_msg = f"ERROR: {type(e).__name__}: {str(e)}"
        print(f"❌ global_ai ERROR: {error_msg}")
        return {"error": error_msg}

# ========================= LOCAL AI =========================
@app.post("/local-ai")
async def local_ai(request: AIRequest):
    import json, requests
    print(f"🏠 Local AI | Q: {request.question} | Model: {request.model}")

    def generate_local():
        try:
            model_name = request.model or "llama3"
            if "llama" in model_name.lower(): model_name = "llama3"
            elif "mistral" in model_name.lower(): model_name = "mistral"

            ollama_url = "http://localhost:11434/api/generate"
            payload = {"model": model_name, "prompt": request.question, "stream": True}
            
            with requests.post(ollama_url, json=payload, stream=True, timeout=5) as r:
                r.raise_for_status()
                for line in r.iter_lines():
                    if line:
                        data = json.loads(line.decode('utf-8'))
                        if "response" in data:
                            yield data["response"]
                            
        except Exception as e:
            print(f"❌ Local AI Error: {e} - Falling back to Cloud AI")
            try:
                from services.global_ai import global_chat
                fallback_ans = global_chat(request.question, request.file_text, request.has_file,
                                           "llama-3.1-70b-versatile",
                                           user_email=request.user_email or "")
                yield f"[Fallback to Cloud] {fallback_ans}"
            except QuotaExceededError as quota_e:
                yield f"ERROR: [quota:{quota_e.scope}] {quota_e}"
            except Exception as fallback_e:
                yield f"❌ Local execution failed and Cloud API fallback error: {str(fallback_e)}"

    return StreamingResponse(generate_local(), media_type="text/plain")

# ========================= UPLOAD DOCUMENT =========================
from fastapi import UploadFile, File

@app.post("/upload-doc", response_model=None)
async def upload_doc(file: UploadFile = File(...)):
    import uuid, base64
    try:
        unique_name = str(uuid.uuid4()) + "_" + file.filename
        file_path = os.path.join(UPLOAD_DIR, unique_name)

        # Read raw bytes
        raw = await file.read()

        # ✅ FIX: if file was accidentally base64-encoded, decode it first
        try:
            if not raw.startswith(b'%PDF'):
                decoded = base64.b64decode(raw)
                if decoded.startswith(b'%PDF'):
                    raw = decoded
                    print("✅ Detected base64 PDF — decoded successfully")
        except Exception:
            pass  # not base64, use raw as-is

        with open(file_path, "wb") as f:
            f.write(raw)

        print(f"📁 Saved file: {file_path} ({len(raw)} bytes)")

        from rag.vector_store import create_vector_store
        rag_index, rag_chunks, rag_model = create_vector_store(file_path)
        rag_cache[file_path] = (rag_index, rag_chunks, rag_model)

        print("✅ RAG index created")
        
        # Free up memory immediately after heavy model activity
        gc.collect()
        
        return {"message": "Document processed successfully", "file_path": file_path}

    except Exception as e:
        print("❌ Upload ERROR:", str(e))
        return {"message": "Failed to process document", "error": str(e)}

# ========================= RAG ASK (SECRET AI) =========================
def _pgvector_context(document_key, local_path, question):
    """Lazy-index a document into pgvector (once) and return the top-k retrieved
    chunks assembled into a context string, ordered by document position.

    Returns None when there is nothing to retrieve, so the caller can fall back
    to full-text mode. Any exception propagates for the same fail-open reason.
    """
    from rag.pgvector_store import is_indexed, index_document, retrieve
    from core.embeddings import embed_texts, embed_query

    if not is_indexed(document_key):
        from rag.document_loader import load_pdf
        from rag.text_splitter import split_text
        text = load_pdf(local_path)
        chunks = split_text(text) if text else []
        if not chunks:
            return None
        print(f"🧩 Indexing {len(chunks)} chunks into pgvector for {document_key}")
        embeddings = embed_texts(chunks, input_type="passage")
        index_document(document_key, chunks, embeddings)
        gc.collect()

    qvec = embed_query(question)
    top_chunks = retrieve(document_key, qvec, k=RAG_TOP_K)
    if not top_chunks:
        return None

    context = ""
    for chunk in top_chunks:
        if context and len(context) + len(chunk) + 2 > RAG_CONTEXT_CHARS:
            break
        context += ("\n\n" if context else "") + chunk
    print(f"🔎 Retrieved {len(top_chunks)} chunks → context {len(context)} chars")
    return context.strip() or None


@app.post("/rag-ask")
async def rag_ask(request: RagRequest):
    import base64
    import urllib.parse
    try:
        print("📨 Question:", request.question)
        print("📄 File:", request.file_path)

        file_path = request.file_path

        # Handle remote URLs (Cloud Migration Fix)
        if file_path.startswith("http"):
            print(f"🌐 Remote document detected: {file_path}")
            import urllib.request, uuid
            
            # Properly encode URL: decode first to normalize, then re-encode only the path
            decoded_url = urllib.parse.unquote(file_path)
            parsed = urllib.parse.urlparse(decoded_url)
            # Re-encode the path portion so spaces become %20
            encoded_path = urllib.parse.quote(parsed.path, safe="/:@!$&'()*+,;=-._~")
            safe_url = urllib.parse.urlunparse((parsed.scheme, parsed.netloc, encoded_path, parsed.params, parsed.query, parsed.fragment))
            print(f"🔗 Encoded URL: {safe_url}")
            
            local_filename = f"remote_{uuid.uuid4()}.pdf"
            local_path = os.path.join(UPLOAD_DIR, local_filename)
            try:
                req = urllib.request.Request(safe_url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=30) as response:
                    with open(local_path, 'wb') as out_file:
                        out_file.write(response.read())
                file_path = local_path
                print(f"📥 Downloaded to: {file_path}")
            except Exception as download_err:
                error_msg = f"ERROR: DownloadError: Failed to download document from storage: {str(download_err)}"
                print(f"❌ {error_msg}")
                return StreamingResponse(
                    iter([error_msg]),
                    media_type="text/plain",
                    status_code=500
                )

        # ✅ FIX: validate file exists and is real PDF before processing
        if not os.path.exists(file_path):
            error_msg = "ERROR: FileNotFoundError: File not found. Please re-upload your document."
            print(f"❌ {error_msg}")
            return StreamingResponse(
                iter([error_msg]),
                media_type="text/plain",
                status_code=404
            )

        with open(file_path, "rb") as f:
            header = f.read(5)

        if not header.startswith(b'%PDF'):
            # Try to fix it on-the-fly
            with open(file_path, "rb") as f:
                raw = f.read()
            try:
                decoded = base64.b64decode(raw)
                if decoded.startswith(b'%PDF'):
                    with open(file_path, "wb") as f:
                        f.write(decoded)
                    print("✅ Fixed corrupted PDF in-place")
                    # Clear cache so it rebuilds
                    with rag_cache_lock:
                        rag_cache.pop(file_path, None)
                else:
                    error_msg = "ERROR: CorruptedFileError: This file appears corrupted. Please re-upload a valid PDF."
                    print(f"❌ {error_msg}")
                    return StreamingResponse(
                        iter([error_msg]),
                        media_type="text/plain",
                        status_code=400
                    )
            except Exception:
                error_msg = "ERROR: CorruptedFileError: This file appears corrupted. Please re-upload a valid PDF."
                print(f"❌ {error_msg}")
                return StreamingResponse(
                    iter([error_msg]),
                    media_type="text/plain",
                    status_code=400
                )

        # ---- Retrieval: pgvector semantic RAG, fail-open to full-text ----
        # document_key is the STABLE original path/URL. The local temp file gets
        # a fresh random name every request, which is why the old cache (keyed by
        # the temp path) never hit — we key everything by document_key now.
        document_key = request.file_path
        doc_text = None

        if RAG_MODE == "pgvector":
            try:
                doc_text = _pgvector_context(document_key, file_path, request.question)
            except Exception as rag_err:
                print(f"⚠ pgvector RAG failed → full-text fallback: {rag_err}")
                doc_text = None

        if doc_text is None:
            # Full-text fallback (original behaviour): extract + truncate, cached by key.
            with rag_cache_lock:
                if document_key in rag_cache:
                    print("⚡ Using previously loaded document text")
                    doc_text = rag_cache[document_key]
                else:
                    print("⏳ Extracting document text (full-text mode)")
                    from rag.document_loader import load_pdf
                    doc_text = load_pdf(file_path)[:30000]
                    rag_cache[document_key] = doc_text
                    gc.collect()

        # Chat history keyed by session_id (per-chat memory, thread-safe)
        session_key = str(request.session_id) if request.session_id else file_path
        print(f"🔑 Session key: {session_key}")
        
        # Initialize history list in dict if not present (thread-safe)
        with doc_history_lock:
            if session_key not in doc_chat_history:
                doc_chat_history[session_key] = []
                # Load existing messages from DB via Spring Boot API
                if request.session_id:
                    try:
                        import urllib.request, json as _json
                        db_url = f"{BACKEND_URL}/api/messages/{request.session_id}"
                        req = urllib.request.Request(db_url)
                        with urllib.request.urlopen(req, timeout=3) as resp:
                            db_msgs = _json.loads(resp.read().decode())
                        # Pair user+ai messages into history entries
                        i = 0
                        while i < len(db_msgs) - 1:
                            if db_msgs[i].get("role") == "user" and db_msgs[i+1].get("role") == "ai":
                                doc_chat_history[session_key].append({
                                    "q": db_msgs[i]["content"],
                                    "a": db_msgs[i+1]["content"]
                                })
                                i += 2
                            else:
                                i += 1
                        print(f"📥 Loaded {len(doc_chat_history[session_key])} history pairs from DB for session {session_key}")
                    except Exception as e:
                        print(f"⚠ Could not load history from DB: {e}")
            history = doc_chat_history[session_key]
        
        print(f"📜 Chat history length: {len(history)}")

        def generate():
            try:
                # Build context with history
                history_text = ""
                if history:
                    history_lines = []
                    for h in history[-5:]:
                        history_lines.append(f"User: {h['q']}")
                        ans = h['a'][:300] if len(h['a']) > 300 else h['a']
                        history_lines.append(f"Assistant: {ans}")
                    history_text = "\n".join(history_lines)
                
                # Build question with history context
                if history_text:
                    question_with_history = f"""<conversation_history>
{history_text}
</conversation_history>

User: {request.question}"""
                else:
                    question_with_history = request.question

                from services.global_ai import global_chat
                full_answer = global_chat(
                    question_with_history,  # Question with history
                    doc_text,               # Pass the document text
                    True,                   # has_file=True to trigger document-only mode
                    request.model,
                    user_email=request.user_email or ""
                )

                yield full_answer

                # Append to the shared list reference (thread-safe)
                with doc_history_lock:
                    history.append({"q": request.question, "a": full_answer})
                print(f"✅ Saved to history. New length: {len(history)}")

            except QuotaExceededError as e:
                # In-band error, same convention as the Ignite streams — the
                # frontend surfaces the engine's message and stops retrying.
                yield f"ERROR: [quota:{e.scope}] {e}"
            except Exception as e:
                error_msg = f"ERROR: {type(e).__name__}: {str(e)}"
                print(f"❌ generate ERROR: {error_msg}")
                yield error_msg
            finally:
                gc.collect()

        return StreamingResponse(generate(), media_type="text/plain")

    except Exception as e:
        error_msg = f"ERROR: {type(e).__name__}: {str(e)}"
        print(f"❌ rag_ask ERROR: {error_msg}")
        return StreamingResponse(
            iter([error_msg]),
            media_type="text/plain",
            status_code=500
        )

# ========================= CODE ANALYZER =========================
@app.post("/code-analyze")
async def code_analyze(request: CodeRequest):
    import re, json
    try:
        lang_map = {"cpp": "C++", "c": "C", "javascript": "JavaScript"}
        language = lang_map.get(request.language, request.language)
        code = request.code[:5000]

        patterns = detect_pattern(code)
        optimization_hint = build_optimization_hint(patterns)

        before_time = "O(?)"
        before_space = "O(?)"

        system_prompt = f"""You are an expert {language} performance engineer and code reviewer.
Analyze code precisely. Return ONLY valid JSON — no markdown, no extra text.
Be accurate with complexity analysis. Never hallucinate optimizations
Improvements must be specific to THIS code — never generic advice like 'add error handling'."""

        prompt = f"""Analyze and optimize this {language} code.

Detected patterns:
{optimization_hint if optimization_hint else "None"}

Rules:
- Only optimize if genuinely better
- Return actual optimized code, not the same code
- Complexity must match the optimized_code exactly
- If already optimal, say so clearly

Return this exact JSON structure:
{{
  "errors": "No errors found OR list real errors",
  "improvements": "- point1\\n- point2\\n- point3",
  "optimized_code": "full improved code here",
  "before_time": "O(...) of the ORIGINAL code",
  "before_space": "O(...) of the ORIGINAL code",
  "time_complexity": "O(...) of the OPTIMIZED code",
  "space_complexity": "O(...) of the OPTIMIZED code",
  "data_structure": "Array / HashMap / Set / None",
  "explanation": "step-by-step explanation"
}}

CODE:
{code}"""

        from core.llm import safe_generate
        raw_output = safe_generate(prompt, request.model, system=system_prompt,
                                   user_email=request.user_email or "")

        if not raw_output:
            raise Exception("No response from AI")

        cleaned = re.sub(r"```json\s*", "", raw_output.strip())
        cleaned = re.sub(r"```", "", cleaned)
        match = re.search(r"\{.*\}", cleaned, re.DOTALL)
        if not match:
            raise Exception("No JSON found in response")

        try:
            parsed = json.loads(match.group())
        except Exception:
            print("⚠ JSON parse failed — using fallback")
            parsed = {
                "errors": "", "improvements": "", "optimized_code": code,
                "time_complexity": before_time, "space_complexity": before_space, "explanation": ""
            }

        optimized_code = parsed.get("optimized_code", "").replace("\\n", "\n")
        improvements   = parsed.get("improvements", "").replace("\\n", "\n")
        after_time     = parsed.get("time_complexity", "O(?)")
        after_space    = parsed.get("space_complexity", "O(?)")
        before_time    = parsed.get("before_time", before_time)
        before_space   = parsed.get("before_space", before_space)

        if optimized_code.replace(" ", "") == code.replace(" ", ""):
            improvements += "\n- ⚠ No major optimization detected"

        improvements = "\n".join(
            dict.fromkeys([l.strip() for l in improvements.split("\n") if l.strip()])
        )
        improvements += f"\n- ⏱ Time: {before_time} → {after_time}"
        improvements += f"\n- 💾 Space: {before_space} → {after_space}"

        return {
            "errors": parsed.get("errors", ""),
            "improvements": improvements,
            "optimized_code": optimized_code,
            "time_complexity": after_time,
            "space_complexity": after_space,
            "before_time": before_time,
            "before_space": before_space,
            "explanation": parsed.get("explanation", "")
        }

    except QuotaExceededError as e:
        return quota_response(e)
    except Exception as e:
        print("❌ code_analyze ERROR:", str(e))
        return {
            "errors": "Analysis failed", "improvements": "", "optimized_code": "",
            "time_complexity": "", "space_complexity": "",
            "before_time": "", "before_space": "", "explanation": ""
        }

# ========================= PROJECT BUILDER (IGNITE) =========================
BUSY_MESSAGE = "The project builder is busy right now. Please try again in a minute."

class ProjectPlanRequest(BaseModel):
    prompt: str
    user_email: Optional[str] = ""

class ProjectFileRequest(BaseModel):
    prompt: str
    file_path: str
    plan_summary: Optional[str] = ""
    file_description: Optional[str] = ""
    files_list: Optional[list] = None
    existing_files: Optional[dict] = None
    user_email: Optional[str] = ""

class ProjectEditRequest(BaseModel):
    prompt: str
    files: Optional[dict] = None
    user_email: Optional[str] = ""

@app.post("/project-builder/plan")
async def project_builder_plan(request: ProjectPlanRequest):
    from fastapi.concurrency import run_in_threadpool
    from core.llm_providers import ProviderBusyError, ProviderError
    from services.project_builder import create_plan
    try:
        return await run_in_threadpool(create_plan, request.prompt,
                                       request.user_email or "")
    except QuotaExceededError as e:
        return quota_response(e)
    except ProviderBusyError:
        return JSONResponse(status_code=429,
                            content={"status": "busy", "message": BUSY_MESSAGE})
    except ProviderError as e:
        print(f"❌ project_builder_plan ERROR: {e}")
        return JSONResponse(status_code=502, content={
            "status": "error",
            "message": "I couldn't create a plan for that project. Please try again."
        })

@app.post("/project-builder/generate-file")
async def project_builder_generate_file(request: ProjectFileRequest):
    from fastapi.concurrency import run_in_threadpool
    from core.llm_providers import ProviderBusyError, ProviderError
    from services.project_builder import generate_file_stream
    try:
        # Priming happens inside generate_file_stream, so provider errors arrive
        # here (with a proper status code) instead of mid-stream.
        stream = await run_in_threadpool(
            generate_file_stream,
            request.prompt,
            request.plan_summary or "",
            request.file_path,
            request.file_description or "",
            request.files_list or [],
            request.existing_files or {},
            request.user_email or "",
        )
        return StreamingResponse(stream, media_type="text/plain")
    except ValueError as e:
        return JSONResponse(status_code=400,
                            content={"status": "error", "message": str(e)})
    except QuotaExceededError as e:
        return quota_response(e)
    except ProviderBusyError:
        return JSONResponse(status_code=429,
                            content={"status": "busy", "message": BUSY_MESSAGE})
    except ProviderError as e:
        print(f"❌ project_builder_generate_file ERROR: {e}")
        return JSONResponse(status_code=502, content={
            "status": "error",
            "message": "File generation failed. Please retry this file."
        })

@app.post("/project-builder/edit")
async def project_builder_edit(request: ProjectEditRequest):
    from fastapi.concurrency import run_in_threadpool
    from core.llm_providers import ProviderBusyError, ProviderError
    from services.project_builder import apply_edit
    try:
        return await run_in_threadpool(apply_edit, request.prompt, request.files or {},
                                       request.user_email or "")
    except ValueError as e:
        return JSONResponse(status_code=400,
                            content={"status": "error", "message": str(e)})
    except QuotaExceededError as e:
        return quota_response(e)
    except ProviderBusyError:
        return JSONResponse(status_code=429,
                            content={"status": "busy", "message": BUSY_MESSAGE})
    except ProviderError as e:
        print(f"❌ project_builder_edit ERROR: {e}")
        return JSONResponse(status_code=502, content={
            "status": "error",
            "message": "I couldn't apply that change. Please try rephrasing it."
        })

@app.get("/project-builder/usage")
async def project_builder_usage(user_email: str = ""):
    from fastapi.concurrency import run_in_threadpool
    from services.token_ledger import get_usage
    try:
        return await run_in_threadpool(get_usage, user_email)
    except Exception as e:
        print(f"❌ project_builder_usage ERROR: {e}")
        return JSONResponse(status_code=502, content={
            "status": "error", "message": "Could not read token usage right now."
        })

# ========================= CIRCUIT =========================
class CircuitAskRequest(BaseModel):
    prompt: str
    context: Optional[str] = ""
    user_email: Optional[str] = ""

@app.post("/circuit/ask")
async def circuit_ask(request: CircuitAskRequest):
    from fastapi.concurrency import run_in_threadpool
    from services.circuit import CircuitError, create_circuit
    try:
        return await run_in_threadpool(create_circuit, request.prompt,
                                       request.user_email or "",
                                       request.context or "")
    except QuotaExceededError as e:
        return quota_response(e)
    except CircuitError as e:
        print(f"❌ circuit_ask ERROR: {e}")
        return JSONResponse(status_code=502, content={
            "status": "error",
            "message": "I couldn't draw that circuit. Please try rephrasing your question."
        })