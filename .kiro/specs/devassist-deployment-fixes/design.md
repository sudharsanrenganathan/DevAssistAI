# DevAssist Deployment Fixes - Bugfix Design

## Overview

The DevAssist AI application experiences intermittent failures in the Secret AI (RAG) feature due to streaming response handling issues, timeout problems, URL encoding conflicts, and inadequate error handling. The root causes are:

1. **Streaming Mismatch**: Backend uses `RestTemplate.postForObject()` which expects complete responses, but AI Engine returns `StreamingResponse` with `media_type="text/plain"`, causing the backend to receive incomplete data and attempt JSON parsing on plain text
2. **Missing Timeout Configuration**: No timeout settings on RestTemplate cause indefinite hangs on large document processing
3. **Double URL Encoding**: Backend pre-encodes filenames before passing to RestTemplate, which auto-encodes again, resulting in `%2520` instead of `%20`
4. **Inadequate Error Context**: Generic error messages without response content make debugging impossible
5. **Non-Thread-Safe Cache**: Python dictionaries used for RAG cache without synchronization primitives

This design provides targeted fixes for each issue while preserving all working functionality (authentication, global AI, document upload, database operations).

## Glossary

- **Bug_Condition (C)**: The condition that triggers failures - when Secret AI RAG requests are made with streaming responses, large documents, special characters in filenames, or concurrent access
- **Property (P)**: The desired behavior - RAG requests complete successfully with full responses, proper timeout handling, correct URL encoding, detailed error messages, and thread-safe caching
- **Preservation**: All working features (authentication, global AI, document upload/delete, session management, database operations) must remain unchanged
- **RestTemplate**: Spring's synchronous HTTP client that blocks until the complete response is received - incompatible with streaming responses
- **StreamingResponse**: FastAPI's response type that sends data incrementally - requires streaming-capable clients
- **RAG Cache**: In-memory dictionary storing vector stores keyed by file path - currently not thread-safe
- **URL Encoding**: Converting special characters to percent-encoded format (e.g., space → `%20`) - must happen exactly once

## Bug Details

### Bug Condition

The bugs manifest in five distinct scenarios:

**1. Streaming Response Handling**
- WHEN backend calls `/rag-ask` with `RestTemplate.postForObject(aiUrl, entity, String.class)`
- AND AI Engine returns `StreamingResponse(generate(), media_type="text/plain")`
- THEN RestTemplate receives incomplete data before stream finishes
- AND backend attempts to parse plain text as JSON
- RESULTING IN "SyntaxError: JSON.parse: unexpected end of data" or "unexpected character at line 1"

**2. Timeout Issues**
- WHEN user uploads PDF >5MB requiring >30 seconds to process
- AND RestTemplate has no configured timeout (defaults to infinite but connection may timeout)
- THEN request hangs indefinitely or times out at network layer
- RESULTING IN frontend showing loading spinner forever

**3. URL Encoding**
- WHEN filename contains spaces or special characters (e.g., "My Document.pdf")
- AND backend pre-encodes: `URLEncoder.encode(filename, "UTF-8")` → "My%20Document.pdf"
- AND RestTemplate auto-encodes the URL string again → "My%2520Document.pdf"
- THEN AI Engine cannot find file at double-encoded path
- RESULTING IN "can't contain control characters" or "file not found" errors

**4. Authentication/CORS**
- WHEN request is made to `/ai/**` endpoints
- AND JWT filter processes request before `permitAll()` check
- THEN 403 Forbidden errors occur intermittently
- OR CORS preflight fails with 400 Bad Request

**5. Error Handling**
- WHEN AI Engine encounters error (file not found, PDF corrupted, processing failure)
- AND returns error in streaming format without structured response
- THEN backend shows generic "AI Engine error: [exception message]"
- WITHOUT actual response content for debugging

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type HttpRequest to /ai/rag/{docId}
  OUTPUT: boolean
  
  RETURN (input.targetEndpoint == "/rag-ask" AND aiEngineReturnsStreamingResponse())
         OR (input.documentSize > 5MB AND restTemplateHasNoTimeout())
         OR (input.filename CONTAINS [' ', '(', ')', '+', '&'] AND urlIsPreEncoded())
         OR (input.endpoint MATCHES "/ai/**" AND jwtFilterInterceptsBeforePermitAll())
         OR (aiEngineReturnsError() AND errorMessageIsGeneric())
END FUNCTION
```

### Examples

**Streaming Issue:**
- User asks: "What is this document about?"
- Backend sends POST to `/rag-ask` with `RestTemplate.postForObject()`
- AI Engine starts streaming: "This document discusses..."
- RestTemplate receives first chunk: "This doc"
- Backend tries: `JSON.parse("This doc")` → SyntaxError
- User sees: "AI Engine error: JSON parse error"

**Timeout Issue:**
- User uploads 8MB PDF with 200 pages
- Backend sends to `/rag-ask`, vector store creation takes 45 seconds
- RestTemplate waits indefinitely (no timeout configured)
- Frontend shows loading spinner for 2+ minutes
- User refreshes page, loses context

**URL Encoding Issue:**
- User uploads: "Project Report (Final).pdf"
- Backend encodes: "Project%20Report%20%28Final%29.pdf"
- RestTemplate encodes again: "Project%2520Report%2520%2528Final%2529.pdf"
- AI Engine tries to download from Supabase with double-encoded URL
- Supabase returns 404
- User sees: "File not found"

**Error Handling Issue:**
- User uploads corrupted PDF (base64-encoded twice)
- AI Engine detects: not starting with `%PDF`
- Returns: `StreamingResponse(iter(["❌ File corrupted"]), media_type="text/plain")`
- Backend catches exception, logs: "AI Engine error: RestClientException"
- User sees generic error, no indication file is corrupted

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Authentication with Supabase JWT must continue to work for protected endpoints
- Document upload to Supabase Storage must continue to save files and return public URLs
- Global AI chat (`/global-ai`) must continue to return responses correctly
- Database operations (save chat, retrieve history, session management) must continue to work
- Frontend navigation and page loading must remain unchanged
- Health check endpoint must continue to return without authentication
- CORS middleware must continue to allow cross-origin requests

**Scope:**
All inputs that do NOT involve Secret AI RAG requests (`/ai/rag/{docId}`) should be completely unaffected by this fix. This includes:
- Global AI requests (`/ai/ask/{userId}/{sessionId}`)
- Document upload/delete operations (`/ai/upload/{userId}`, `/ai/documents/{id}`)
- Session management (`/ai/session/**`, `/ai/sessions/**`, `/ai/messages/**`)
- Health checks and root endpoints
- Authentication flows

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

1. **Streaming Response Incompatibility**
   - `RestTemplate.postForObject()` is designed for complete, non-streaming responses
   - It blocks until response is complete, but with `StreamingResponse`, "complete" is ambiguous
   - The method expects a specific type (String.class) and tries to deserialize immediately
   - AI Engine returns `media_type="text/plain"` but backend expects JSON structure

2. **Missing RestTemplate Configuration**
   - Default `RestTemplate` instance created with `new RestTemplate()` has no timeout settings
   - No connection timeout, read timeout, or request factory configuration
   - Large document processing (vector store creation) can take 30-60 seconds
   - Without timeout, requests hang indefinitely or fail at network layer

3. **URL Encoding Layering Issue**
   - Backend code manually encodes filename: `URLEncoder.encode(filename, "UTF-8")`
   - Then passes encoded string to RestTemplate
   - RestTemplate's HTTP client (default is SimpleClientHttpRequestFactory) auto-encodes URLs
   - Result: double encoding (`%20` becomes `%2520`)
   - AI Engine receives double-encoded URL, Supabase Storage returns 404

4. **JWT Filter Order**
   - Spring Security filter chain processes JWT filter before checking `permitAll()` configuration
   - JWT filter attempts to extract and validate token for `/ai/**` endpoints
   - If token is missing or invalid, filter rejects request before reaching controller
   - `SecurityConfig.permitAll()` is evaluated too late in the chain

5. **Generic Exception Handling**
   - Backend catches all exceptions with generic `catch (Exception e)`
   - Error message only includes `e.getMessage()` without response body
   - AI Engine returns errors in streaming format (plain text with "❌" prefix)
   - Backend cannot distinguish between network errors, parsing errors, and application errors
   - No logging of actual response content for debugging

6. **Non-Thread-Safe Cache**
   - Python dictionaries (`rag_cache`, `doc_chat_history`, `global_chat_history`) are not thread-safe
   - Multiple concurrent requests can cause race conditions during cache updates
   - While Python GIL provides some protection, dictionary operations are not atomic
   - Potential for corrupted cache state or lost updates

## Correctness Properties

Property 1: Bug Condition - RAG Requests Complete Successfully

_For any_ HTTP request to `/ai/rag/{docId}` where the AI Engine returns a streaming text response, the fixed backend SHALL consume the entire stream, return the complete text wrapped in a JSON object `{"answer": "..."}`, handle timeouts gracefully with 60-second read timeout, properly encode URLs only once, and provide detailed error messages including response content for debugging.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.7, 2.8, 2.12, 2.13, 2.14**

Property 2: Preservation - Non-RAG Functionality Unchanged

_For any_ HTTP request that is NOT to `/ai/rag/{docId}` (including global AI, document upload/delete, session management, authentication, health checks), the fixed code SHALL produce exactly the same behavior as the original code, preserving all existing functionality for non-RAG endpoints.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `backend/devassist-backend/src/main/java/com/devassist/backend/controller/AiController.java`

**Function**: `askRag()`

**Specific Changes**:

1. **Replace RestTemplate with Streaming-Capable Client**
   - Remove: `String answer = restTemplate.postForObject(aiUrl, entity, String.class);`
   - Add: Configure RestTemplate with `BufferingClientHttpRequestFactory` or use `RestClient` (Spring 6.1+)
   - Alternative: Use `RestTemplate.execute()` with custom `ResponseExtractor` to read full stream
   - Implementation:
     ```java
     // Configure RestTemplate with timeout and buffering
     SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
     factory.setConnectTimeout(10000); // 10 seconds
     factory.setReadTimeout(60000);    // 60 seconds
     RestTemplate restTemplate = new RestTemplate(factory);
     
     // Use execute() to manually read stream
     String answer = restTemplate.execute(aiUrl, HttpMethod.POST, 
         request -> {
             request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
             objectMapper.writeValue(request.getBody(), requestBody);
         },
         response -> {
             // Read entire stream into string
             return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
         }
     );
     ```

2. **Remove Manual URL Encoding**
   - Remove: `encodedFilePath = baseUrl + java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");`
   - Change: Pass raw file path directly to request body
   - Let RestTemplate's HTTP client handle URL encoding automatically
   - Implementation:
     ```java
     String filePath = doc.getFilePath(); // Use raw path, no encoding
     Map<String, Object> requestBody = Map.of(
         "question", question,
         "file_path", filePath, // Raw path, not encoded
         "session_id", docId
     );
     ```

3. **Add Detailed Error Logging**
   - Change catch block to log response content
   - Include first 200 characters of actual response in error message
   - Implementation:
     ```java
     } catch (Exception e) {
         String errorMsg = "RAG request failed: " + e.getMessage();
         if (e instanceof HttpClientErrorException) {
             HttpClientErrorException httpEx = (HttpClientErrorException) e;
             String responseBody = httpEx.getResponseBodyAsString();
             String preview = responseBody.length() > 200 
                 ? responseBody.substring(0, 200) + "..." 
                 : responseBody;
             errorMsg += " | Response: " + preview;
         }
         System.out.println("❌ " + errorMsg);
         throw new RuntimeException(errorMsg);
     }
     ```

4. **Move RestTemplate to Bean Configuration**
   - Remove: `private final RestTemplate restTemplate = new RestTemplate();`
   - Add: Create `@Bean` in `AppConfig.java` with timeout configuration
   - Inject configured RestTemplate via `@Autowired`
   - Implementation in `AppConfig.java`:
     ```java
     @Bean
     public RestTemplate restTemplate() {
         SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
         factory.setConnectTimeout(10000);  // 10 seconds
         factory.setReadTimeout(60000);     // 60 seconds for large documents
         factory.setBufferRequestBody(false); // Don't buffer for streaming
         return new RestTemplate(factory);
     }
     ```

**File**: `backend/devassist-backend/src/main/java/com/devassist/backend/config/SecurityConfig.java`

**Function**: `filterChain()`

**Specific Changes**:

5. **Fix JWT Filter Order**
   - Ensure `/ai/**` endpoints skip JWT filter before authentication check
   - Add explicit filter ordering or use `@Order` annotation
   - Implementation:
     ```java
     @Bean
     public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
         http
             .csrf(csrf -> csrf.disable())
             .authorizeHttpRequests(auth -> auth
                 .requestMatchers("/ai/**", "/health", "/").permitAll()
                 .anyRequest().authenticated()
             )
             .addFilterBefore(jwtAuthenticationFilter(), 
                 UsernamePasswordAuthenticationFilter.class);
         return http.build();
     }
     
     // Modify JWT filter to skip /ai/** paths
     public class JwtAuthenticationFilter extends OncePerRequestFilter {
         @Override
         protected boolean shouldNotFilter(HttpServletRequest request) {
             String path = request.getRequestURI();
             return path.startsWith("/ai/") || path.equals("/health") || path.equals("/");
         }
     }
     ```

**File**: `ai-engine/api/api_server.py`

**Function**: `rag_ask()`

**Specific Changes**:

6. **Add Thread-Safe Cache**
   - Replace: `rag_cache = {}`
   - Add: `from threading import Lock` and `rag_cache_lock = Lock()`
   - Wrap cache access with lock
   - Implementation:
     ```python
     from threading import Lock
     
     rag_cache = {}
     rag_cache_lock = Lock()
     doc_chat_history = {}
     doc_history_lock = Lock()
     
     # In rag_ask():
     with rag_cache_lock:
         if file_path in rag_cache:
             rag_index, rag_chunks, rag_model = rag_cache[file_path]
         else:
             rag_index, rag_chunks, rag_model = create_vector_store(file_path)
             rag_cache[file_path] = (rag_index, rag_chunks, rag_model)
     
     with doc_history_lock:
         if session_key not in doc_chat_history:
             doc_chat_history[session_key] = []
         history = doc_chat_history[session_key]
     ```

7. **Improve Error Response Format**
   - Add structured error responses with recognizable markers
   - Prefix errors with "ERROR:" for backend detection
   - Implementation:
     ```python
     # In rag_ask() exception handler:
     except Exception as e:
         error_msg = f"ERROR: {type(e).__name__}: {str(e)}"
         print(f"❌ rag_ask ERROR: {error_msg}")
         return StreamingResponse(
             iter([error_msg]),
             media_type="text/plain",
             status_code=500
         )
     ```

8. **Fix URL Encoding in File Download**
   - Ensure `urllib.request.urlretrieve()` handles encoded URLs correctly
   - Add URL decoding if needed before download
   - Implementation:
     ```python
     if file_path.startswith("http"):
         import urllib.parse
         # Decode if already encoded, then let urlretrieve handle encoding
         decoded_url = urllib.parse.unquote(file_path)
         urllib.request.urlretrieve(decoded_url, local_path)
     ```

**File**: `backend/devassist-backend/src/main/java/com/devassist/backend/config/CorsConfig.java`

**Function**: `corsConfigurer()`

**Specific Changes**:

9. **Ensure Proper CORS Headers**
   - Verify all required headers are included
   - Add explicit `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`
   - Implementation:
     ```java
     @Bean
     public WebMvcConfigurer corsConfigurer() {
         return new WebMvcConfigurer() {
             @Override
             public void addCorsMappings(CorsRegistry registry) {
                 registry.addMapping("/**")
                     .allowedOrigins("*")
                     .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                     .allowedHeaders("*")
                     .exposedHeaders("Authorization")
                     .maxAge(3600);
             }
         };
     }
     ```

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write integration tests that simulate RAG requests with various conditions (streaming responses, large documents, special characters, concurrent access). Run these tests on the UNFIXED code to observe failures and understand the root causes.

**Test Cases**:
1. **Streaming Response Test**: Send RAG request, verify backend receives complete response (will fail on unfixed code with JSON parse error)
2. **Large Document Test**: Upload 8MB PDF, send RAG request, measure response time (will timeout or hang on unfixed code)
3. **Special Characters Test**: Upload "Test Document (Final).pdf", send RAG request (will fail with file not found on unfixed code)
4. **Concurrent Access Test**: Send 5 simultaneous RAG requests to same document (may cause race conditions on unfixed code)
5. **Error Response Test**: Trigger AI Engine error (corrupted PDF), verify error message includes details (will show generic error on unfixed code)

**Expected Counterexamples**:
- Backend receives incomplete streaming response and attempts JSON parsing
- Requests timeout after 30+ seconds with no configured timeout
- Double-encoded URLs cause file not found errors
- Generic error messages without response content
- Possible causes: RestTemplate incompatibility with streaming, missing timeout config, double URL encoding, inadequate error handling

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := handleRagRequest_fixed(input)
  ASSERT result.statusCode == 200
  ASSERT result.body.answer IS NOT NULL
  ASSERT result.body.answer.length > 0
  ASSERT result.responseTime < 60000 // 60 seconds
  ASSERT NOT result.body.answer.contains("JSON parse error")
  ASSERT NOT result.body.answer.contains("file not found") OR input.fileExists == false
END FOR
```

**Test Cases**:
1. **Streaming Success**: Send RAG request, verify complete response received in <60s
2. **Large Document Success**: Upload 8MB PDF, verify response within 60s timeout
3. **Special Characters Success**: Upload "My Document (2024).pdf", verify file found and processed
4. **Concurrent Access Success**: Send 10 simultaneous requests, verify all succeed without race conditions
5. **Detailed Error Messages**: Trigger error, verify error message includes response preview

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT handleRequest_original(input) = handleRequest_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for non-RAG endpoints, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Global AI Preservation**: Verify `/ai/ask/{userId}/{sessionId}` continues to work exactly as before
2. **Document Upload Preservation**: Verify `/ai/upload/{userId}` continues to save files and return URLs
3. **Document Delete Preservation**: Verify `/ai/documents/{id}` continues to delete files
4. **Session Management Preservation**: Verify session CRUD operations continue to work
5. **Authentication Preservation**: Verify JWT authentication continues to work for protected endpoints
6. **Health Check Preservation**: Verify `/health` continues to return without authentication

### Unit Tests

- Test RestTemplate configuration with timeout settings
- Test URL encoding/decoding logic in isolation
- Test error message formatting with response preview
- Test JWT filter skip logic for `/ai/**` paths
- Test thread-safe cache access with concurrent operations
- Test streaming response reading with `ResponseExtractor`

### Property-Based Tests

- Generate random RAG requests with various document sizes and verify all complete within timeout
- Generate random filenames with special characters and verify correct encoding
- Generate random concurrent request patterns and verify thread-safe cache behavior
- Generate random error scenarios and verify detailed error messages

### Integration Tests

- Test full RAG flow: upload document → ask question → receive answer
- Test timeout handling: upload large document → verify response within 60s or timeout error
- Test error propagation: trigger AI Engine error → verify detailed error message in backend
- Test concurrent access: multiple users asking questions about same document simultaneously
- Test CORS: send preflight OPTIONS request → verify proper headers returned
