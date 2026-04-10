# Implementation Plan

## Overview

This implementation plan follows the exploratory bugfix workflow: Explore → Preserve → Implement → Validate. We will write tests BEFORE implementing fixes to understand the bugs, then verify the fixes work correctly without breaking existing functionality.

---

## Phase 1: Exploration - Understand the Bugs

### 1. Write Bug Condition Exploration Tests

- [ ] 1.1 Streaming Response Bug Exploration Test
  - **Property 1: Bug Condition** - Streaming Response Handling Failure
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the streaming response bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing cases - RAG requests that return streaming text responses
  - Test implementation: Send POST request to `/ai/rag/{docId}` with a valid question
  - Assert that response is received completely (not truncated)
  - Assert that response does NOT contain "JSON parse error" or "unexpected character"
  - Assert that response body contains actual answer text (length > 0)
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS with JSON parse errors or incomplete responses (this is correct - it proves the bug exists)
  - Document counterexamples found: "Backend receives partial streaming response 'This doc' and attempts JSON.parse(), causing SyntaxError"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 1.2 Timeout Bug Exploration Test
  - **Property 1: Bug Condition** - Large Document Timeout Failure
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the timeout bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing case - upload large PDF (>5MB) and send RAG request
  - Test implementation: Upload 8MB PDF document, send RAG request
  - Assert that response is received within 60 seconds
  - Assert that response does NOT timeout or hang indefinitely
  - Measure actual response time
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS with timeout or indefinite hang (this is correct - it proves the bug exists)
  - Document counterexamples found: "Request to process 8MB PDF hangs for 120+ seconds with no configured timeout"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.4, 1.5_

- [ ] 1.3 URL Encoding Bug Exploration Test
  - **Property 1: Bug Condition** - Double URL Encoding Failure
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the URL encoding bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing cases - filenames with spaces and special characters
  - Test implementation: Upload document with filename "Test Document (Final).pdf"
  - Send RAG request referencing this document
  - Assert that file is found and processed correctly
  - Assert that response does NOT contain "file not found" or "can't contain control characters"
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS with file not found errors due to double encoding (this is correct - it proves the bug exists)
  - Document counterexamples found: "Filename 'Test Document.pdf' encoded as 'Test%2520Document.pdf' (double-encoded), AI Engine cannot find file"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.7, 1.8, 1.9_

- [ ] 1.4 Concurrent Access Bug Exploration Test
  - **Property 1: Bug Condition** - Non-Thread-Safe Cache Race Condition
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the thread-safety bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing case - 10 simultaneous requests to same document
  - Test implementation: Send 10 concurrent RAG requests to the same document
  - Assert that all requests complete successfully without errors
  - Assert that no race conditions occur in cache access
  - Assert that all responses are valid and complete
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test MAY FAIL with race conditions or corrupted cache state (this confirms the bug exists)
  - Document counterexamples found: "Concurrent requests cause cache corruption or lost updates in Python dictionary"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.6_

- [ ] 1.5 Error Handling Bug Exploration Test
  - **Property 1: Bug Condition** - Generic Error Messages Without Details
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the error handling bug exists
  - **Scoped PBT Approach**: Scope the property to concrete failing case - trigger AI Engine error (corrupted PDF)
  - Test implementation: Upload corrupted PDF (invalid format), send RAG request
  - Assert that error message includes specific details about the failure
  - Assert that error message includes preview of actual response content (first 200 chars)
  - Assert that error message does NOT show only generic "AI Engine error: RestClientException"
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS with generic error messages (this is correct - it proves the bug exists)
  - Document counterexamples found: "Corrupted PDF triggers error, but backend only shows 'AI Engine error: RestClientException' without response content"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.12, 1.13, 1.14_

---

## Phase 2: Preservation - Capture Existing Behavior

### 2. Write Preservation Property Tests (BEFORE implementing fix)

- [ ] 2.1 Global AI Preservation Test
  - **Property 2: Preservation** - Global AI Chat Continues to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Send request to `/ai/ask/{userId}/{sessionId}` with question "Hello" on unfixed code
  - Observe: Response returns successfully with AI-generated answer
  - Write property-based test: For all valid Global AI requests (non-RAG), response is successful and contains answer text
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.3, 3.9_

- [ ] 2.2 Document Upload Preservation Test
  - **Property 2: Preservation** - Document Upload Continues to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Upload document via `/ai/upload/{userId}` on unfixed code
  - Observe: Document is saved to Supabase Storage, metadata saved to database, public URL returned
  - Write property-based test: For all valid document uploads, file is saved and URL is returned
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.2_

- [ ] 2.3 Authentication Preservation Test
  - **Property 2: Preservation** - JWT Authentication Continues to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Send request to protected endpoint with valid JWT on unfixed code
  - Observe: Request is authenticated successfully and returns expected response
  - Write property-based test: For all requests with valid JWT to protected endpoints, authentication succeeds
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.1_

- [ ] 2.4 Health Check Preservation Test
  - **Property 2: Preservation** - Health Check Continues to Work Without Auth
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Send GET request to `/health` without authentication on unfixed code
  - Observe: Response returns `{"status": "healthy"}` without requiring JWT
  - Write property-based test: For all health check requests, response is successful without authentication
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.4_

- [ ] 2.5 Database Operations Preservation Test
  - **Property 2: Preservation** - Chat History Storage Continues to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Save chat message to database, retrieve chat history on unfixed code
  - Observe: Messages are stored with correct timestamps and retrieved in chronological order
  - Write property-based test: For all chat save/retrieve operations, data is persisted and retrieved correctly
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.5, 3.6_

- [ ] 2.6 Frontend Navigation Preservation Test
  - **Property 2: Preservation** - Page Navigation Continues to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Navigate between pages (login, dashboard, global chat, secret chat) on unfixed code
  - Observe: Pages load correctly with proper authentication checks
  - Write property-based test: For all navigation actions, pages load correctly
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.7, 3.8_

- [ ] 2.7 CORS Configuration Preservation Test
  - **Property 2: Preservation** - CORS Headers Continue to Work
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Send OPTIONS preflight request from different origin on unfixed code
  - Observe: Response includes proper CORS headers allowing cross-origin requests
  - Write property-based test: For all CORS preflight requests, proper headers are returned
  - Verify test passes on UNFIXED code
  - **EXPECTED OUTCOME**: Test PASSES (this confirms baseline behavior to preserve)
  - Mark task complete when test is written, run, and passing on unfixed code
  - _Requirements: 3.12_

---

## Phase 3: Implementation - Apply the Fixes

### 3. Backend Fixes (Java Spring Boot)

- [x] 3.1 Configure RestTemplate with Timeout and Streaming Support
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/AppConfig.java`
  - Create `@Bean` method for RestTemplate configuration
  - Set connection timeout to 10 seconds: `factory.setConnectTimeout(10000)`
  - Set read timeout to 60 seconds: `factory.setReadTimeout(60000)`
  - Use `SimpleClientHttpRequestFactory` for timeout configuration
  - Configure factory to not buffer request body: `factory.setBufferRequestBody(false)`
  - Return configured RestTemplate instance
  - _Bug_Condition: isBugCondition(input) where input.targetEndpoint == "/rag-ask" AND restTemplateHasNoTimeout()_
  - _Expected_Behavior: RestTemplate configured with 60-second read timeout allows large document processing_
  - _Preservation: Global AI and other endpoints continue to use same RestTemplate with proper timeouts_
  - _Requirements: 1.4, 1.5, 2.4, 3.3_

- [x] 3.2 Replace RestTemplate.postForObject() with Streaming-Capable Client
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/controller/AiController.java`
  - **Function**: `askRag()`
  - Remove: `String answer = restTemplate.postForObject(aiUrl, entity, String.class);`
  - Replace with: `restTemplate.execute()` using custom `ResponseExtractor`
  - Implementation approach:
    ```java
    String answer = restTemplate.execute(aiUrl, HttpMethod.POST,
        request -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            objectMapper.writeValue(request.getBody(), requestBody);
        },
        response -> {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        }
    );
    ```
  - Read entire stream into string using `readAllBytes()`
  - Handle streaming response as plain text, not JSON
  - Inject configured RestTemplate via `@Autowired` instead of creating new instance
  - _Bug_Condition: isBugCondition(input) where input.targetEndpoint == "/rag-ask" AND aiEngineReturnsStreamingResponse()_
  - _Expected_Behavior: Backend consumes entire streaming response and returns complete text_
  - _Preservation: Global AI endpoint continues to use postForObject() for non-streaming responses_
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

- [x] 3.3 Remove Manual URL Encoding from File Path
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/controller/AiController.java`
  - **Function**: `askRag()`
  - Remove: `encodedFilePath = baseUrl + java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20");`
  - Change: Pass raw file path directly in request body
  - Implementation: `String filePath = doc.getFilePath(); // Use raw path, no encoding`
  - Update request body: `Map.of("question", question, "file_path", filePath, "session_id", docId)`
  - Let RestTemplate's HTTP client handle URL encoding automatically
  - _Bug_Condition: isBugCondition(input) where input.filename CONTAINS [' ', '(', ')', '+', '&'] AND urlIsPreEncoded()_
  - _Expected_Behavior: File path sent as raw URL, HTTP client handles encoding once_
  - _Preservation: Document upload/delete operations continue to work with file paths_
  - _Requirements: 1.7, 1.8, 2.7, 2.8_

- [x] 3.4 Add Detailed Error Logging with Response Preview
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/controller/AiController.java`
  - **Function**: `askRag()` catch block
  - Change catch block to log response content
  - Check if exception is `HttpClientErrorException` and extract response body
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
  - _Bug_Condition: isBugCondition(input) where aiEngineReturnsError() AND errorMessageIsGeneric()_
  - _Expected_Behavior: Error messages include response preview for debugging_
  - _Preservation: Other error handling continues to work as before_
  - _Requirements: 1.12, 1.13, 2.12, 2.13_

- [x] 3.5 Fix JWT Filter Order for /ai/** Endpoints
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/config/SecurityConfig.java`
  - **Function**: `filterChain()`
  - Ensure `/ai/**` endpoints skip JWT filter before authentication check
  - Modify JWT filter to implement `shouldNotFilter()` method
  - Implementation:
    ```java
    public class JwtAuthenticationFilter extends OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            return path.startsWith("/ai/") || path.equals("/health") || path.equals("/");
        }
    }
    ```
  - Verify filter chain order: JWT filter added before UsernamePasswordAuthenticationFilter
  - Verify `permitAll()` configuration includes `/ai/**`, `/health`, `/`
  - _Bug_Condition: isBugCondition(input) where input.endpoint MATCHES "/ai/**" AND jwtFilterInterceptsBeforePermitAll()_
  - _Expected_Behavior: JWT filter skips /ai/** endpoints, permitAll() works correctly_
  - _Preservation: Authentication for protected endpoints continues to work_
  - _Requirements: 1.10, 2.10, 3.1_

- [x] 3.6 Verify CORS Configuration
  - **File**: `backend/devassist-backend/src/main/java/com/devassist/backend/config/CorsConfig.java`
  - **Function**: `corsConfigurer()`
  - Verify all required headers are included
  - Ensure `allowedOrigins("*")` for development
  - Ensure `allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")`
  - Ensure `allowedHeaders("*")` and `exposedHeaders("Authorization")`
  - Set `maxAge(3600)` for preflight cache
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
  - _Bug_Condition: isBugCondition(input) where CORS preflight fails with 400 Bad Request_
  - _Expected_Behavior: CORS headers allow cross-origin requests_
  - _Preservation: Existing CORS configuration continues to work_
  - _Requirements: 1.11, 2.11, 3.12_

### 4. AI Engine Fixes (Python FastAPI)

- [x] 4.1 Add Thread-Safe Cache with Locks
  - **File**: `ai-engine/api/api_server.py`
  - **Function**: `rag_ask()`
  - Import threading module: `from threading import Lock`
  - Create lock objects: `rag_cache_lock = Lock()`, `doc_history_lock = Lock()`
  - Wrap cache access with lock:
    ```python
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
  - Apply same pattern to `global_chat_history` in `ask_question()`
  - _Bug_Condition: isBugCondition(input) where multiple concurrent requests access same cache_
  - _Expected_Behavior: Cache operations are thread-safe, no race conditions_
  - _Preservation: Cache functionality continues to work, just with thread safety_
  - _Requirements: 1.6, 2.6_

- [x] 4.2 Improve Error Response Format with Recognizable Markers
  - **File**: `ai-engine/api/api_server.py`
  - **Function**: `rag_ask()` exception handler
  - Add structured error responses with "ERROR:" prefix
  - Implementation:
    ```python
    except Exception as e:
        error_msg = f"ERROR: {type(e).__name__}: {str(e)}"
        print(f"❌ rag_ask ERROR: {error_msg}")
        return StreamingResponse(
            iter([error_msg]),
            media_type="text/plain",
            status_code=500
        )
    ```
  - Apply same pattern to `ask_question()` for consistency
  - _Bug_Condition: isBugCondition(input) where aiEngineReturnsError() AND backend cannot detect error_
  - _Expected_Behavior: Error messages prefixed with "ERROR:" for backend detection_
  - _Preservation: Error handling continues to work, just with better format_
  - _Requirements: 1.14, 2.14_

- [x] 4.3 Fix URL Encoding in File Download
  - **File**: `ai-engine/rag/document_loader.py` or `ai-engine/api/api_server.py`
  - **Function**: File download logic in `rag_ask()`
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
  - Test with filenames containing spaces and special characters
  - _Bug_Condition: isBugCondition(input) where input.filename CONTAINS special characters AND URL is encoded_
  - _Expected_Behavior: File downloads correctly with proper URL handling_
  - _Preservation: File download for normal filenames continues to work_
  - _Requirements: 1.9, 2.9_

### 5. Verification Sub-Tasks

- [ ] 5.1 Verify Bug Condition Exploration Tests Now Pass
  - **Property 1: Expected Behavior** - All Bug Condition Tests Pass After Fix
  - **IMPORTANT**: Re-run the SAME tests from Phase 1 - do NOT write new tests
  - The tests from Phase 1 encode the expected behavior
  - When these tests pass, it confirms the expected behavior is satisfied
  - Run all bug condition exploration tests from Phase 1 (tasks 1.1-1.5)
  - **EXPECTED OUTCOME**: All tests PASS (confirms bugs are fixed)
  - Verify streaming response test passes (complete response received)
  - Verify timeout test passes (response within 60 seconds)
  - Verify URL encoding test passes (file found and processed)
  - Verify concurrent access test passes (no race conditions)
  - Verify error handling test passes (detailed error messages)
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 2.7, 2.8, 2.12, 2.13, 2.14_

- [ ] 5.2 Verify Preservation Tests Still Pass
  - **Property 2: Preservation** - All Preservation Tests Pass After Fix
  - **IMPORTANT**: Re-run the SAME tests from Phase 2 - do NOT write new tests
  - Run all preservation property tests from Phase 2 (tasks 2.1-2.7)
  - **EXPECTED OUTCOME**: All tests PASS (confirms no regressions)
  - Verify Global AI preservation test passes
  - Verify document upload preservation test passes
  - Verify authentication preservation test passes
  - Verify health check preservation test passes
  - Verify database operations preservation test passes
  - Verify frontend navigation preservation test passes
  - Verify CORS configuration preservation test passes
  - Confirm all tests still pass after fix (no regressions)
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12_

---

## Phase 4: Validation

### 6. Final Checkpoint

- [ ] 6.1 Run Full Test Suite
  - Execute all unit tests for backend and AI engine
  - Execute all integration tests for RAG flow
  - Execute all property-based tests for bug conditions and preservation
  - Verify all tests pass without failures
  - Check test coverage for modified code (aim for >80%)
  - _Requirements: All requirements 1.1-3.12_

- [ ] 6.2 Manual Testing and Verification
  - Test RAG flow end-to-end: upload document → ask question → receive answer
  - Test with large documents (>5MB) to verify timeout handling
  - Test with filenames containing spaces and special characters
  - Test concurrent access with multiple users
  - Test error scenarios (corrupted PDF, invalid file)
  - Verify all working features still work (Global AI, document upload, authentication)
  - _Requirements: All requirements 1.1-3.12_

- [ ] 6.3 Review and Documentation
  - Review all code changes for correctness and best practices
  - Ensure error messages are clear and helpful
  - Verify logging is adequate for debugging
  - Update any relevant documentation or comments
  - Confirm all requirements are satisfied
  - Ask user if any questions or issues arise
  - _Requirements: All requirements 1.1-3.12_

---

## Notes

- **Test-First Approach**: All exploration and preservation tests MUST be written and run BEFORE implementing fixes
- **Expected Failures**: Exploration tests SHOULD fail on unfixed code - this confirms the bugs exist
- **Expected Passes**: Preservation tests SHOULD pass on unfixed code - this confirms baseline behavior
- **No Premature Fixes**: Do NOT fix bugs while writing exploration tests - document failures instead
- **Verification**: After fixes, re-run the SAME tests to verify bugs are fixed and behavior is preserved
- **Property-Based Testing**: Use property-based testing for stronger guarantees across input domains
- **Thread Safety**: Python dictionaries are not thread-safe - use locks for concurrent access
- **URL Encoding**: Encode URLs only once at HTTP client level, not in application code
- **Streaming Responses**: Use streaming-capable HTTP clients (RestTemplate.execute()) for streaming responses
- **Error Handling**: Include response content in error messages for debugging
