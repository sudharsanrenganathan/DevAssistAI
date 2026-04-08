import numpy as np
from core.llm import safe_generate

# ========================= STREAM HELPERS =========================

def stream_answer(prompt, model_name=None, system=None):
    result = safe_generate(prompt, model_name, system=system)
    yield result

def stream_not_found():
    yield (
        "I couldn't find that in your document.\n\n"
        "Try **Global AI** from the top bar — it can answer anything beyond your document."
    )

def stream_greeting(model_name=None):
    system = """You are SecretAI, a precise document analyst assistant.
Keep greetings warm but brief — 2 sentences max.
Never use filler like "Certainly!" or "Of course!"."""

    prompt = "The user just greeted you. Greet them back briefly and tell them you're ready to help with their document."
    yield from stream_answer(prompt, model_name, system=system)

# ========================= GREETING DETECTOR =========================
GREETINGS = {
    "hi", "hello", "hey", "hii", "helo", "hai", "howdy", "sup",
    "good morning", "good evening", "good afternoon", "good night",
    "greetings", "what's up", "whats up", "yo", "hiya", "heya",
    "hi there", "hello there", "hey there"
}

def is_greeting(question: str) -> bool:
    q = question.strip().lower().rstrip("!.,?")
    return q in GREETINGS

# ========================= REACTIONS =========================
REACTIONS = {
    "ok", "okay", "ohho", "ohh", "oh", "alright", "fine", "got it",
    "noted", "thanks", "thank you", "thx", "cool", "nice", "great",
    "wow", "awesome", "sure", "ohhh", "ohho ok", "ok ok", "ohk",
    "understood", "i see", "makes sense", "good", "perfect"
}

# ========================= QUESTION NORMALIZER =========================
def normalize_question(question: str, chunks: list, model_name=None) -> str:
    sample_context = " ".join(chunks[:3])[:600]
    prompt = f"""You are a query optimizer for a document Q&A system.

User asked: "{question}"

Rules:
- If already a clear question → return it exactly as-is
- If vague → rewrite as a specific question using the document sample
- If greeting → return exactly: GREETING

Document sample:
\"\"\"{sample_context}\"\"\"

Return ONLY the rewritten question or GREETING. No explanation."""

    return safe_generate(prompt, model_name)

# ========================= MAIN RAG STREAM =========================
def ask_question_stream(question, index, chunks, model, model_name=None, chat_history=None):

    if chat_history is None:
        chat_history = []

    # Greeting check
    if is_greeting(question):
        yield from stream_greeting(model_name)
        return

    # Reaction check — only shortcut if no chat history (otherwise user might be asking about prior context)
    q_clean = question.strip().lower().rstrip("!.,?")
    if q_clean in REACTIONS and not chat_history:
        system = """You are SecretAI. Be brief and natural.
No filler openers. Reply in 1 sentence and invite the next question."""
        prompt = f'User said: "{question}". Reply naturally and invite their next question about the document.'
        yield from stream_answer(prompt, model_name, system=system)
        return

    # ✅ Conversational/memory questions — use history directly, skip vector search
    MEMORY_PATTERNS = [
        "my name", "who am i", "what's my name", "whats my name", "what is my name",
        "do you remember", "remember me", "what did i say", "what did i ask",
        "what did i tell", "earlier i said", "i told you", "you said", "you told me",
        "what was my", "who was i", "recall", "i mentioned", "previously",
        # Personal statements that should be remembered
        "my name is", "i am ", "i'm ", "call me", "i live in", "i work at",
        "i study", "i like", "i love", "i hate", "i want", "i need",
    ]
    q_lower = question.strip().lower()
    is_memory_question = any(p in q_lower for p in MEMORY_PATTERNS)
    
    if is_memory_question and chat_history:
        # Build history and answer directly from memory — no document needed
        history_text = ""
        history_lines = []
        for h in chat_history[-8:]:
            history_lines.append(f"User: {h['q']}")
            ans = h['a'][:500] if len(h['a']) > 500 else h['a']
            history_lines.append(f"Assistant: {ans}")
        history_text = "\n".join(history_lines)
        
        system = """You are SecretAI. You have perfect memory of the current conversation.
Answer the user's question using ONLY the conversation history below.
If the information was shared in the conversation, recall it accurately.
Be natural and direct. Never say you can't remember if it's in the history."""
        
        prompt = f"""<conversation_history>
{history_text}
</conversation_history>

User question: {question}"""
        yield from stream_answer(prompt, model_name, system=system)
        return
    
    if is_memory_question and not chat_history:
        # First personal statement (e.g. "my name is Sudhar") — acknowledge it
        system = """You are SecretAI, a friendly document analyst.
The user is sharing personal information with you. Acknowledge it warmly in 1-2 sentences.
Remember what they tell you. Then invite them to ask about their document."""
        prompt = f'User said: "{question}". Acknowledge what they shared and invite them to ask about their document.'
        yield from stream_answer(prompt, model_name, system=system)
        return

    # Normalize question
    normalized = normalize_question(question, chunks, model_name)

    if normalized.strip().upper() == "GREETING":
        yield from stream_greeting(model_name)
        return

    search_question = normalized if normalized else question

    # Vector search
    question_embedding = model.encode([search_question])
    question_embedding = np.array(question_embedding).astype("float32")
    D, I = index.search(question_embedding, k=4)

    selected_chunks = [chunks[i] for i in I[0] if i < len(chunks)]
    context = "\n\n".join(selected_chunks[:4])[:4000]

    if not context.strip():
        yield from stream_not_found()
        return

    # ✅ SYSTEM PROMPT — Claude/ChatGPT/NotebookLM style for document Q&A
    system_prompt = """You are SecretAI — a precise, expert document analyst, like NotebookLM combined with Claude.

SOURCE RULES (non-negotiable):
- Answer from the provided document context OR from the conversation history.
- For document questions: use ONLY the document context. Never use outside knowledge.
- For conversational questions (greetings, name recall, follow-ups): use the conversation history.
- If the answer is not in either the document context or conversation history → respond: NOT_FOUND
- Never guess, never hallucinate facts, names, numbers, or dates.

CONVERSATION MEMORY (important):
- You have access to the conversation history from this chat session.
- Use it to maintain context — if the user told you their name, remember it.
- If the user refers to something discussed earlier, use the history to answer.
- Each chat session is independent — don't mix up conversations.

RESPONSE LENGTH — adapt to the question:
- Short/precise question → short precise answer (1-3 sentences max)
- "Explain", "elaborate", "tell me more", "in detail" → structured answer with key points
- Summary request → concise structured overview with sections
- After a SHORT answer → end with: "Want me to elaborate on any part?"
- After a LONG answer → end with: "Need more detail on a specific section?"

STYLE:
- Answer directly — never start with "Based on the context..." or "According to the document..."
- No filler openers: never say "Great question!", "Certainly!", "Sure!", "Of course!"
- Never repeat the question back
- Use **bold** for key terms and important values
- Use bullet points for 3+ distinct items
- Use code blocks for any code, commands, or technical values
- Be confident and precise like a senior analyst who read the entire document"""

    # Build conversation history string for context
    history_text = ""
    if chat_history:
        history_lines = []
        for h in chat_history[-5:]:  # last 5 exchanges
            history_lines.append(f"User: {h['q']}")
            # Truncate long answers to keep prompt manageable
            ans = h['a'][:500] if len(h['a']) > 500 else h['a']
            history_lines.append(f"Assistant: {ans}")
        history_text = "\n".join(history_lines)

    prompt = f"""<context>
{context}
</context>

<conversation_history>
{history_text if history_text else "No previous conversation."}
</conversation_history>

User question: {question}"""

    # Stream answer
    full_answer = ""
    for chunk in stream_answer(prompt, model_name, system=system_prompt):
        full_answer += chunk
        yield chunk

    # Handle NOT_FOUND
    if "NOT_FOUND" in full_answer.upper():
        yield from stream_not_found()

# ========================= NON-STREAM =========================
def ask_question(question, index, chunks, model):
    question_embedding = model.encode([question])
    question_embedding = np.array(question_embedding).astype("float32")
    D, I = index.search(question_embedding, k=3)
    context = "\n\n".join([chunks[i] for i in I[0]])

    system_prompt = """You are SecretAI — a precise document analyst.
Answer ONLY from the provided context.
If not found → say NOT_FOUND.
Be concise and accurate."""

    prompt = f"""<context>
{context}
</context>

Question: {question}"""

    answer = safe_generate(prompt, None, system=system_prompt)

    if "NOT_FOUND" in answer.upper():
        return "I couldn't find that in your document."

    return answer

# ========================= TEST MODE =========================
if __name__ == "__main__":
    print("\n🔐 Secret Intelligence Mode Activated")
    while True:
        question = input("\nAsk: ")
        for chunk in ask_question_stream(question, rag_index, rag_chunks, rag_model):
            print(chunk, end="", flush=True)
        print()