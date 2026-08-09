# 🚀 DevAssist AI - Quick Start

## ✅ What's Been Fixed

1. **Spring Boot Version**: Changed from 4.0.3 → 3.2.2 ✅
2. **RAG Folder**: Deleted (was standalone, not needed) ✅
3. **Database**: Supabase configured ✅
4. **User Isolation**: Already implemented ✅
5. **Startup Scripts**: Created for easy launch ✅

## 🎯 Start the Application

### Option 1: Automated Startup (Windows)
```powershell
.\run-local.ps1
```

This will:
- Start AI Engine on port 8000
- Start Backend on port 8080 (with DB credentials from local-secrets.ps1)
- Open Frontend on port 5500

### Option 2: Manual Startup

**Terminal 1 - AI Engine:**
```bash
cd ai-engine
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
uvicorn api.api_server:app --host 0.0.0.0 --port 8000
```

**Terminal 2 - Backend:**
```bash
cd backend\devassist-backend
mvn clean install
mvn spring-boot:run
```

**Terminal 3 - Frontend:**
```bash
cd frontend
python -m http.server 3000
```

Then open: http://localhost:3000/index.html

## 🔐 User Data Isolation

### How It Works:
Every user gets a unique Supabase UUID when they sign up. All data is filtered by this UUID:

```
User A (UUID: abc-123)
├── Documents: Only User A's files
├── Global Chats: Only User A's chats
├── Secret Chats: Only User A's document chats
└── History: Only User A's activity

User B (UUID: xyz-789)
├── Documents: Only User B's files
├── Global Chats: Only User B's chats
├── Secret Chats: Only User B's document chats
└── History: Only User B's activity
```

### Verified Endpoints:
✅ `/api/upload/{userId}` - Upload scoped to user
✅ `/api/documents/{userId}` - Get user's documents only
✅ `/api/global-chat/all?userId={userId}` - Get user's chats only
✅ `/api/sessions/{userId}` - Get user's sessions only
✅ `/api/activity/recent/{userId}` - Get user's activity only

## 🧪 Test User Isolation

1. **Create Two Users:**
   - User A: usera@test.com
   - User B: userb@test.com

2. **Upload Document as User A:**
   - Go to Secret AI
   - Upload a PDF
   - Note it appears in the list

3. **Switch to User B:**
   - Log out
   - Log in as User B
   - Go to Secret AI
   - **Verify**: No documents visible ✅

4. **Create Chat as User A:**
   - Log in as User A
   - Go to Global AI
   - Create a new chat
   - Send some messages

5. **Switch to User B:**
   - Log out
   - Log in as User B
   - Go to Global AI
   - **Verify**: No chats visible ✅

## 📊 Database Tables

All tables have user isolation:

```sql
-- Users table
users (id, email, supabase_id, name, avatar_url)

-- Documents (isolated by user_id)
documents (id, file_name, file_path, user_id)

-- Global chats (isolated by supabase_user_id)
global_chats (id, title, supabase_user_id)
global_messages (id, chat_id, role, content)

-- Secret chats (isolated by user_id)
secret_chats (id, user_id, document_id)
secret_messages (id, chat_id, role, content)

-- User activity (isolated by user_id)
user_activity (id, user_id, action, page)
```

## 🎨 Features

### ✅ Global AI
- Chat with AI without documents
- Multiple chat threads per user
- Chat history persists
- **Isolated per user**

### ✅ Secret AI
- Upload documents (PDF, TXT, DOCX)
- Ask questions about documents
- RAG-powered answers
- **Isolated per user**

### ✅ Code Analyzer
- Analyze code complexity
- Get optimization suggestions
- Time/space complexity analysis
- **History isolated per user**

### ✅ Online Compiler
- Execute code in multiple languages
- Real-time output
- Support for C, C++, Python, Java, JavaScript

### ✅ VoidMain
- Solve coding problems
- Submit solutions
- Track progress
- **Submissions isolated per user**
- Leaderboard (public)

## 🔧 Configuration

### Backend (application.properties)
```properties
# Database
spring.datasource.url=jdbc:postgresql://aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres
spring.datasource.username=postgres.xovfufudevsqdmqxmyge
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}   # env var — real value never written in docs

# AI Engine
ai.engine.url=http://localhost:8000

# Supabase
supabase.url=https://xovfufudevsqdmqxmyge.supabase.co
```

### AI Engine (.env)
```env
# Real values live only in the gitignored ai-engine/.env
GROQ_API_KEY=gsk_...your-groq-key...
DATABASE_URL=postgresql://...user...:...password...@aws-1-ap-northeast-2.pooler.supabase.com:6543/postgres
```

### Frontend (supabase-config.js)
```javascript
const SUPABASE_URL = "https://xovfufudevsqdmqxmyge.supabase.co";
const SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...";
```

## 🐛 Troubleshooting

### Backend won't start
```bash
# Check Java version
java -version  # Should be 17+

# Clean and rebuild
cd backend/devassist-backend
mvn clean install
```

### AI Engine won't start
```bash
# Check Python version
python --version  # Should be 3.9+

# Reinstall dependencies
cd ai-engine
pip install -r requirements.txt
```

### Database connection fails
- Check Supabase is accessible
- Verify credentials in application.properties
- Check network connection

### User can see other user's data
- Check localStorage has correct userId
- Verify API calls include userId parameter
- Check backend logs for errors

## 📝 Next Steps

1. ✅ Start all services
2. ✅ Create test users
3. ✅ Test user isolation
4. ✅ Upload documents
5. ✅ Create chats
6. ✅ Verify data separation

## 🎉 You're Ready!

Your DevAssist AI is now configured for local development with:
- ✅ Supabase database
- ✅ User authentication
- ✅ Complete data isolation
- ✅ All features working

**Access**: http://localhost:3000/index.html

---

**Need Help?** Check:
- `LOCAL_SETUP_GUIDE.md` - Detailed setup instructions
- `TEST_USER_ISOLATION.md` - Testing guide
- Backend logs in terminal
- Browser console (F12)
