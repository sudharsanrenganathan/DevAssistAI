# 🚀 DevAssist AI - Local Setup Guide

## 📋 Overview
This guide will help you run DevAssist AI locally with Supabase database and proper user data isolation.

## ✅ Current Status
- **Database**: Supabase PostgreSQL (configured)
- **Authentication**: Supabase Auth (configured)
- **Backend**: Spring Boot (Java) - needs version fix
- **AI Engine**: FastAPI (Python) - working
- **Frontend**: HTML/JS - working

## 🔧 Prerequisites
- Java 17 or higher
- Maven 3.6+
- Python 3.9+
- Node.js 18+ (optional, for future enhancements)

## 📊 Database Schema (Supabase)

### User Data Isolation Strategy
All tables use `user_id` or `supabase_user_id` for data isolation:

```
users
├── id (primary key)
├── email (unique)
├── supabase_id (unique) ← Links to Supabase Auth
├── name
├── avatar_url
└── provider (google/email)

documents
├── id
├── user_id ← Isolates documents per user
├── file_name
├── file_path
└── uploaded_at

global_chats
├── id
├── supabase_user_id ← Isolates chats per user
├── title
├── is_pinned
└── created_at

global_messages
├── id
├── chat_id ← Links to global_chats
├── role (user/ai)
└── content

secret_chats
├── id
├── user_id ← Isolates secret chats per user
├── document_id
└── created_at

secret_messages
├── id
├── chat_id ← Links to secret_chats
├── role (user/ai)
└── content
```

## 🛠️ Setup Steps

### Step 1: Fix Spring Boot Version
**Issue**: pom.xml uses non-existent version 4.0.3
**Fix**: Change to 3.2.2 (latest stable)

### Step 2: Configure Environment Variables
> 🔒 **Real values are NEVER written in this file.** Locally they live in `ai-engine/.env`
> (gitignored) and in your shell environment for the backend. In production they are set
> in the Render dashboard (see `render.yaml` for the list).

**Backend** — set as environment variables before starting (values in your password manager / Supabase dashboard):
```properties
spring.datasource.url=jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres
spring.datasource.username=postgres.xovfufudevsqdmqxmyge
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}   # env var — do not write the real password here
```

**AI Engine** (`ai-engine/.env` — gitignored, keep real values only there):
```env
GROQ_API_KEY=gsk_...your-groq-key...
DATABASE_URL=postgresql://...user...:...password...@aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres

# Ignite project builder — active provider is Groq (gpt-oss-120b).
# Each provider has its own model variable; add future providers to
# PROVIDER_REGISTRY in ai-engine/core/llm_providers.py.
NVIDIA_NIM_API_KEY=nvapi-...your-key...       # get one at build.nvidia.com (nvidia provider only)
PROJECT_BUILDER_PROVIDER=groq                 # set to "nvidia" to switch back to NVIDIA NIM
PROJECT_BUILDER_MODEL_GROQ=openai/gpt-oss-120b
PROJECT_BUILDER_MODEL_NVIDIA=deepseek-ai/deepseek-v4-pro

# Shared global token pool with per-user hard quota, covering ALL LLM features
# (Global AI, doc chat, code analyzer, Ignite). Nothing is pre-allocated —
# tokens leave the shared pool only when actually consumed; the per-user limit
# is just a daily ceiling. Ledgers live in Supabase (migration:
# db/migrations/2026-07-06_create_daily_usage_tables.sql). Resets at midnight
# UTC automatically (rows are keyed by date).
AI_PROVIDER_DAILY_TOKENS=200000     # provider's daily cap (Groq free tier)
AI_EMERGENCY_RESERVE_TOKENS=10000   # never spent — absorbs in-flight overshoot
AI_USER_DAILY_TOKENS=25000          # per-user daily ceiling
# Usable global pool = provider − reserve = 190,000. Override explicitly with
# AI_GLOBAL_DAILY_TOKENS only if you need a different pool size.

# ---- Secret AI RAG (pgvector semantic retrieval) ----
# Embeddings come from the Supabase gte-small edge function
# (supabase/functions/embed), so no ML libraries run in the engine. Before use:
#   1) apply db/migrations/2026-08-09_create_document_chunks.sql in Supabase
#   2) deploy the edge function:  supabase functions deploy embed
RAG_MODE=pgvector                   # set to "fulltext" to disable retrieval (old 30K-char mode)
RAG_TOP_K=6                         # chunks retrieved per question
RAG_CONTEXT_CHARS=8000              # max characters of retrieved context sent to the LLM
EMBEDDING_EDGE_URL=https://<project>.supabase.co/functions/v1/embed
EMBEDDING_EDGE_KEY=eyJ...supabase-anon-key...   # anon (or service role) key for the edge function
```

**Frontend** (`frontend/supabase-config.js`):
```javascript
// Already configured ✅
const SUPABASE_URL = "https://xovfufudevsqdmqxmyge.supabase.co";
const SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...";
```

### Step 3: Start Services

#### 1. Start AI Engine (Port 8000)
```bash
cd ai-engine
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate
pip install -r requirements.txt
uvicorn api.api_server:app --host 0.0.0.0 --port 8000
```

#### 2. Start Backend (Port 8080)
```bash
cd backend/devassist-backend
mvn clean install
mvn spring-boot:run
```

#### 3. Open Frontend
```bash
cd frontend
# Open index.html in browser or use live server
```

## 🔐 User Data Isolation

### How It Works:
1. **User logs in** via Supabase Auth (Google/Email)
2. **Supabase returns** user UUID
3. **Frontend stores** UUID in localStorage
4. **All API calls** include user UUID
5. **Backend filters** data by user UUID
6. **Each user sees** only their own data

### Example API Flow:
```javascript
// Frontend
const userId = localStorage.getItem('userId'); // Supabase UUID

// Upload document
fetch(`${BACKEND_URL}/api/upload/${userId}`, {
    method: 'POST',
    body: formData
});

// Get user's documents only
fetch(`${BACKEND_URL}/api/documents/${userId}`);

// Create chat (isolated per user)
fetch(`${BACKEND_URL}/api/global-chat/${userId}`, {
    method: 'POST',
    body: JSON.stringify({ title: 'New Chat' })
});
```

## 🎯 Features with User Isolation

### ✅ Global AI Chat
- Each user has their own chat threads
- Messages are isolated per user
- Chat history persists in Supabase

### ✅ Secret AI (Document Chat)
- Documents are uploaded per user
- Each user sees only their documents
- Chat history linked to user's documents

### ✅ Code Analyzer
- Analysis results can be saved per user
- History tracked per user

### ✅ VoidMain
- Submissions tracked per user
- Leaderboard shows all users (public)
- Individual history per user

## 🐛 Known Issues & Fixes

### Issue 1: Spring Boot Version
**Status**: ❌ Needs fix
**Solution**: Change version in pom.xml from 4.0.3 to 3.2.2

### Issue 2: Database Connection
**Status**: ✅ Configured
**Note**: Using Supabase connection pooler (port 6543)

### Issue 3: CORS
**Status**: ✅ Configured
**Note**: Backend allows all origins for local development

### Issue 4: File Upload Size
**Status**: ✅ Configured
**Note**: Max 100MB per file

## 📝 Testing Checklist

### User Authentication
- [ ] User can sign up with email
- [ ] User can sign in with Google
- [ ] User session persists
- [ ] User can log out

### Data Isolation
- [ ] User A cannot see User B's documents
- [ ] User A cannot see User B's chats
- [ ] User A cannot see User B's history

### Features
- [ ] Global AI chat works
- [ ] Secret AI document upload works
- [ ] Secret AI chat works
- [ ] Code analyzer works
- [ ] Online compiler works
- [ ] VoidMain works

## 🚀 Quick Start Commands

```bash
# Terminal 1: AI Engine
cd ai-engine
source venv/bin/activate
uvicorn api.api_server:app --reload

# Terminal 2: Backend
cd backend/devassist-backend
mvn spring-boot:run

# Terminal 3: Frontend (optional live server)
cd frontend
python -m http.server 3000
```

## 🔗 Access URLs
- **Frontend**: http://localhost:3000 (or open index.html)
- **Backend API**: http://localhost:8080
- **AI Engine**: http://localhost:8000
- **API Docs**: http://localhost:8000/docs

## 📞 Support
If you encounter issues:
1. Check all services are running
2. Verify database connection
3. Check browser console for errors
4. Check backend logs for errors

---

**Last Updated**: 2026-04-29
**Status**: Ready for local development ✅
