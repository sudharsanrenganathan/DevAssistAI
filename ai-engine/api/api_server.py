from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
import os

# ABSOLUTE MINIMAL STARTUP - ALL LIBRARIES LOADED ON-DEMAND

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

# ========================= CACHE =========================
rag_cache = {}
doc_chat_history = {}
global_chat_history = {}

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

# Models moved inside routes or defined minimally
from pydantic import BaseModel
from typing import Optional, Union

class AIRequest(BaseModel):
    question: str
    file_text: Optional[str] = None
    has_file: Optional[bool] = False
    model: Optional[str] = None
    session_id: Optional[str] = None

class RagRequest(BaseModel):
    question: str
    file_path: str
    model: Optional[str] = None
    session_id: Optional[Union[str, int]] = None

class CodeRequest(BaseModel):
    code: str
    language: str
    model: Optional[str] = None

# ========================= DB HELPERS =========================
def get_db():
    import psycopg2
    return psycopg2.connect(os.getenv("DATABASE_URL"))

def get_chat_history(session_id):
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("""
            SELECT role, content FROM global_messages
            WHERE chat_id = %s
            ORDER BY id DESC LIMIT 20
        """, (session_id,))
        rows = list(reversed(cur.fetchall()))
        cur.close()
        conn.close()

        history = []
        i = 0
        while i < len(rows) - 1:
            role1, content1 = rows[i]
            role2, content2 = rows[i + 1]
            if role1 == "user" and role2 == "ai":
                history.append({"question": content1, "answer": content2})
                i += 2
            else:
                i += 1
        return history
    except Exception as e:
        print(f"⚠ get_chat_history error: {e}")
        return []

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

    # In-memory per-chat history
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

    from services.global_ai import global_chat
    answer = global_chat(
        full_prompt,
        request.file_text,
        request.has_file,
        request.model
    )

    # Save to in-memory history
    history.append({"q": request.question, "a": answer})
    print(f"✅ Global history saved. New length: {len(history)}")

    # Also save to DB for persistence
    try:
        session_int = int(request.session_id) if request.session_id else 1
        save_chat(session_int, request.question, answer)
    except Exception as e:
        print(f"⚠ DB save failed (non-critical): {e}")

    return {"answer": answer}

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
                fallback_ans = global_chat(request.question, request.file_text, request.has_file, "llama-3.1-70b-versatile")
                yield f"[Fallback to Cloud] {fallback_ans}"
            except Exception as fallback_e:
                yield f"❌ Local execution failed and Cloud API fallback error: {str(fallback_e)}"

    return StreamingResponse(generate_local(), media_type="text/plain")

# ========================= UPLOAD DOCUMENT =========================
from fastapi import UploadFile, File

@app.post("/upload-doc")
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
        return {"message": "Document processed successfully", "file_path": file_path}

    except Exception as e:
        print("❌ Upload ERROR:", str(e))
        return {"message": "Failed to process document", "error": str(e)}

# ========================= RAG ASK (SECRET AI) =========================
@app.post("/rag-ask")
async def rag_ask(request: RagRequest):
    from rag.question_answering import ask_question_stream
    import base64
    try:
        print("📨 Question:", request.question)
        print("📄 File:", request.file_path)

        file_path = request.file_path

        # Handle remote URLs (Cloud Migration Fix)
        if file_path.startswith("http"):
            print(f"🌐 Remote document detected: {file_path}")
            import urllib.request, uuid
            local_filename = f"remote_{uuid.uuid4()}.pdf"
            local_path = os.path.join(UPLOAD_DIR, local_filename)
            try:
                urllib.request.urlretrieve(file_path, local_path)
                file_path = local_path
                print(f"📥 Downloaded to: {file_path}")
            except Exception as download_err:
                print(f"❌ Download failed: {download_err}")
                return StreamingResponse(
                    iter([f"❌ Failed to download document from storage: {str(download_err)}"]),
                    media_type="text/plain"
                )

        # ✅ FIX: validate file exists and is real PDF before processing
        if not os.path.exists(file_path):
            return StreamingResponse(
                iter(["❌ File not found. Please re-upload your document."]),
                media_type="text/plain"
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
                    rag_cache.pop(file_path, None)
                else:
                    return StreamingResponse(
                        iter(["❌ This file appears corrupted. Please re-upload a valid PDF."]),
                        media_type="text/plain"
                    )
            except Exception:
                return StreamingResponse(
                    iter(["❌ This file appears corrupted. Please re-upload a valid PDF."]),
                    media_type="text/plain"
                )

        # Cache check
        if file_path in rag_cache:
            print("⚡ Using cached vector store")
            rag_index, rag_chunks, rag_model = rag_cache[file_path]
        else:
            print("⏳ Creating new vector store")
            from rag.vector_store import create_vector_store
            rag_index, rag_chunks, rag_model = create_vector_store(file_path)
            rag_cache[file_path] = (rag_index, rag_chunks, rag_model)

        # Chat history keyed by session_id (per-chat memory)
        session_key = str(request.session_id) if request.session_id else file_path
        print(f"🔑 Session key: {session_key}")
        
        # Initialize history list in dict if not present (ensures same list reference)
        if session_key not in doc_chat_history:
            doc_chat_history[session_key] = []
            # ✅ PERSIST FIX: Load existing messages from DB via Spring Boot API
            if request.session_id:
                try:
                    import urllib.request, json as _json
                    db_url = f"{BACKEND_URL}/api/chat/{request.session_id}"
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
            full_answer = ""
            for chunk in ask_question_stream(
                request.question,
                rag_index,
                rag_chunks,
                rag_model,
                model_name=request.model,
                chat_history=list(history)  # pass a snapshot so it doesn't change mid-stream
            ):
                full_answer += chunk
                yield chunk

            # Append to the shared list reference (already in doc_chat_history)
            history.append({"q": request.question, "a": full_answer})
            print(f"✅ Saved to history. New length: {len(history)}")

        return StreamingResponse(generate(), media_type="text/plain")

    except Exception as e:
        print("❌ rag_ask ERROR:", str(e))
        return StreamingResponse(
            iter([f"❌ Error processing document: {str(e)}"]),
            media_type="text/plain"
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
        raw_output = safe_generate(prompt, request.model, system=system_prompt)

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

    except Exception as e:
        print("❌ code_analyze ERROR:", str(e))
        return {
            "errors": "Analysis failed", "improvements": "", "optimized_code": "",
            "time_complexity": "", "space_complexity": "",
            "before_time": "", "before_space": "", "explanation": ""
        }