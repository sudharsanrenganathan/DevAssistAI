# 🧪 User Data Isolation Testing Guide

## Purpose
Verify that each user's data is completely isolated and users cannot access each other's data.

## Test Scenarios

### Scenario 1: User Registration & Login
**Objective**: Verify users can create accounts and log in

#### Test Steps:
1. Open `http://localhost:3000/index.html`
2. Click "Sign Up"
3. Create User A with email: `usera@test.com`
4. Log out
5. Create User B with email: `userb@test.com`
6. Log out

**Expected Result**:
- ✅ Both users created successfully
- ✅ Each user gets unique Supabase UUID
- ✅ Users can log in with their credentials

---

### Scenario 2: Document Upload Isolation
**Objective**: Verify User A cannot see User B's documents

#### Test Steps:
1. **As User A**:
   - Log in as `usera@test.com`
   - Go to Secret AI
   - Upload document: `test_doc_a.pdf`
   - Note the document appears in the list
   - Log out

2. **As User B**:
   - Log in as `userb@test.com`
   - Go to Secret AI
   - Check document list

**Expected Result**:
- ✅ User B sees NO documents
- ✅ User B does NOT see `test_doc_a.pdf`
- ✅ User B can upload their own document

3. **As User A (again)**:
   - Log in as `usera@test.com`
   - Go to Secret AI
   - Verify `test_doc_a.pdf` is still there

**Expected Result**:
- ✅ User A still sees their document
- ✅ User A does NOT see User B's document

---

### Scenario 3: Global Chat Isolation
**Objective**: Verify chat threads are isolated per user

#### Test Steps:
1. **As User A**:
   - Log in as `usera@test.com`
   - Go to Global AI
   - Create chat: "User A's Private Chat"
   - Send message: "This is User A's message"
   - Log out

2. **As User B**:
   - Log in as `userb@test.com`
   - Go to Global AI
   - Check chat list

**Expected Result**:
- ✅ User B sees NO chats
- ✅ User B does NOT see "User A's Private Chat"
- ✅ User B can create their own chat

3. **As User A (again)**:
   - Log in as `usera@test.com`
   - Go to Global AI
   - Verify chat is still there

**Expected Result**:
- ✅ User A sees their chat
- ✅ User A's messages are preserved
- ✅ User A does NOT see User B's chats

---

### Scenario 4: Secret AI Chat Isolation
**Objective**: Verify document chats are isolated per user

#### Test Steps:
1. **As User A**:
   - Log in as `usera@test.com`
   - Go to Secret AI
   - Select their document
   - Ask question: "What is this document about?"
   - Get response
   - Log out

2. **As User B**:
   - Log in as `userb@test.com`
   - Go to Secret AI
   - Try to access User A's document (should fail)

**Expected Result**:
- ✅ User B cannot access User A's document
- ✅ User B cannot see User A's chat history
- ✅ Each user has separate chat history per document

---

### Scenario 5: VoidMain Submission Isolation
**Objective**: Verify code submissions are tracked per user

#### Test Steps:
1. **As User A**:
   - Log in as `usera@test.com`
   - Go to VoidMain
   - Submit a solution
   - Check history

2. **As User B**:
   - Log in as `userb@test.com`
   - Go to VoidMain
   - Check history

**Expected Result**:
- ✅ User B does NOT see User A's submissions
- ✅ Each user has their own submission history
- ✅ Leaderboard shows all users (public data)

---

### Scenario 6: Code Analyzer History Isolation
**Objective**: Verify analysis history is per user

#### Test Steps:
1. **As User A**:
   - Log in as `usera@test.com`
   - Go to Code Analyzer
   - Analyze some code
   - Check if history is saved

2. **As User B**:
   - Log in as `userb@test.com`
   - Go to Code Analyzer
   - Check history

**Expected Result**:
- ✅ User B does NOT see User A's analysis history
- ✅ Each user has separate history

---

## Database Verification

### Check User Isolation in Database

```sql
-- Check users table
SELECT id, email, supabase_id, name FROM users;

-- Check documents per user
SELECT d.id, d.file_name, d.user_id, u.email 
FROM documents d 
JOIN users u ON d.user_id = u.supabase_id;

-- Check global chats per user
SELECT gc.id, gc.title, gc.supabase_user_id, u.email
FROM global_chats gc
JOIN users u ON gc.supabase_user_id = u.supabase_id;

-- Check secret chats per user
SELECT sc.id, sc.user_id, d.file_name, u.email
FROM secret_chats sc
JOIN documents d ON sc.document_id = d.id
JOIN users u ON sc.user_id = u.supabase_id;
```

---

## API Testing with cURL

### Test Document Upload (User A)
```bash
# Get User A's token from browser localStorage
USER_A_ID="<supabase-uuid-user-a>"

curl -X POST http://localhost:8080/api/upload/${USER_A_ID} \
  -F "file=@test_doc_a.pdf"
```

### Test Document List (User A)
```bash
curl http://localhost:8080/api/documents/${USER_A_ID}
```

### Test Document List (User B - should be empty)
```bash
USER_B_ID="<supabase-uuid-user-b>"
curl http://localhost:8080/api/documents/${USER_B_ID}
```

### Test Global Chat Creation (User A)
```bash
curl -X POST http://localhost:8080/api/global-chat/${USER_A_ID} \
  -H "Content-Type: application/json" \
  -d '{"title": "User A Chat"}'
```

### Test Global Chat List (User B - should be empty)
```bash
curl http://localhost:8080/api/global-chat/${USER_B_ID}
```

---

## Security Checklist

### ✅ Data Isolation
- [ ] Users cannot see each other's documents
- [ ] Users cannot see each other's chats
- [ ] Users cannot see each other's messages
- [ ] Users cannot see each other's submissions
- [ ] Users cannot see each other's analysis history

### ✅ Authentication
- [ ] Unauthenticated users cannot access data
- [ ] JWT tokens are validated
- [ ] Session expires after logout
- [ ] Users cannot impersonate other users

### ✅ Authorization
- [ ] Users can only access their own data
- [ ] API endpoints validate user ownership
- [ ] Database queries filter by user_id
- [ ] File uploads are scoped to user

---

## Common Issues & Solutions

### Issue: User sees other user's data
**Cause**: Missing user_id filter in query
**Solution**: Add WHERE clause with user_id

### Issue: Document upload fails
**Cause**: user_id not passed to backend
**Solution**: Verify localStorage has userId

### Issue: Chat history not loading
**Cause**: Incorrect user_id in API call
**Solution**: Check Supabase UUID is correct

---

## Test Results Template

```
Test Date: ___________
Tester: ___________

Scenario 1: User Registration & Login
- User A created: [ ] Pass [ ] Fail
- User B created: [ ] Pass [ ] Fail
- Login works: [ ] Pass [ ] Fail

Scenario 2: Document Upload Isolation
- User A upload: [ ] Pass [ ] Fail
- User B cannot see A's docs: [ ] Pass [ ] Fail
- User A docs persist: [ ] Pass [ ] Fail

Scenario 3: Global Chat Isolation
- User A chat created: [ ] Pass [ ] Fail
- User B cannot see A's chats: [ ] Pass [ ] Fail
- User A chats persist: [ ] Pass [ ] Fail

Scenario 4: Secret AI Chat Isolation
- User A document chat: [ ] Pass [ ] Fail
- User B cannot access: [ ] Pass [ ] Fail

Scenario 5: VoidMain Isolation
- User A submission: [ ] Pass [ ] Fail
- User B cannot see: [ ] Pass [ ] Fail

Scenario 6: Code Analyzer Isolation
- User A history: [ ] Pass [ ] Fail
- User B cannot see: [ ] Pass [ ] Fail

Overall Result: [ ] All Pass [ ] Some Failures
```

---

**Note**: All tests should pass for proper user data isolation. If any test fails, check the backend logs and database queries for missing user_id filters.
