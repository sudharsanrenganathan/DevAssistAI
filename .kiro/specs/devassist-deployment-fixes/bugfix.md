# Bugfix Requirements Document

## Introduction

The DevAssist AI application is deployed across three services (Frontend on Vercel, Backend on Render, AI Engine on Render) with Supabase for database and storage. While basic functionality works (authentication, global AI chat, document upload), the Secret AI (RAG) feature fails intermittently with JSON parsing errors, timeout issues, and streaming response handling problems. This bugfix addresses all deployment and functionality issues to ensure 100% reliability.

**Impact:** Users cannot reliably use the Secret AI feature to ask questions about uploaded documents, which is a core feature of the application. The intermittent failures create a poor user experience and make the application appear broken.

## Bug Analysis

### Current Behavior (Defect)

**1. Streaming Response Handling Issues**

1.1 WHEN the backend calls `/rag-ask` endpoint THEN the system attempts to parse the streaming text response as JSON, causing "SyntaxError: JSON.parse: unexpected end of data at line 1"

1.2 WHEN the AI Engine returns a StreamingResponse with `media_type="text/plain"` THEN the backend's `restTemplate.postForObject()` receives incomplete data before the stream finishes, resulting in partial or empty responses

1.3 WHEN the backend receives streaming text from AI Engine THEN the system tries to extract JSON from plain text, causing "unexpected character at line 1" errors

**2. Timeout and Performance Issues**

1.4 WHEN a user uploads a large PDF (>5MB) THEN the RAG processing takes longer than 30 seconds, causing the backend RestTemplate to timeout with no configured timeout settings

1.5 WHEN the AI Engine processes a large document THEN the vector store creation blocks the response, causing the frontend to show loading indefinitely

1.6 WHEN multiple users access the same document simultaneously THEN the RAG cache is not thread-safe, potentially causing race conditions

**3. URL Encoding and File Path Issues**

1.7 WHEN a filename contains spaces or special characters THEN the file path passed to AI Engine causes "can't contain control characters" errors despite URL encoding attempts

1.8 WHEN the backend encodes the file path using `URLEncoder.encode()` THEN the AI Engine receives double-encoded URLs (e.g., `%2520` instead of `%20`), causing file not found errors

1.9 WHEN the AI Engine downloads a remote file from Supabase Storage THEN URL-encoded filenames fail to download correctly

**4. Authentication and CORS Issues**

1.10 WHEN a request is made to `/ai/**` endpoints THEN the JWT filter sometimes intercepts despite `permitAll()` configuration, causing 403 Forbidden errors

1.11 WHEN the frontend makes cross-origin requests THEN some requests fail with 400 Bad Request due to missing or incorrect CORS headers

**5. Error Handling and Logging Issues**

1.12 WHEN an error occurs in the AI Engine THEN the backend receives a generic error message without specific details, making debugging difficult

1.13 WHEN JSON parsing fails in the backend THEN the error message shows "AI Engine error: JSON parse error" without indicating the actual response content

1.14 WHEN the AI Engine returns an error in streaming format THEN the backend cannot distinguish between successful streaming and error streaming

### Expected Behavior (Correct)

**1. Streaming Response Handling**

2.1 WHEN the backend calls `/rag-ask` endpoint THEN the system SHALL consume the entire streaming response as plain text and return it directly without JSON parsing

2.2 WHEN the AI Engine returns a StreamingResponse THEN the backend SHALL use a streaming-capable HTTP client that waits for the complete response before returning

2.3 WHEN the backend receives streaming text from AI Engine THEN the system SHALL treat it as plain text and wrap it in a JSON response object for the frontend

**2. Timeout and Performance**

2.4 WHEN a user uploads a large PDF THEN the backend SHALL configure RestTemplate with a 60-second read timeout to allow sufficient processing time

2.5 WHEN the AI Engine processes a large document THEN the system SHALL return an immediate acknowledgment and process the document asynchronously, or implement proper streaming with progress updates

2.6 WHEN multiple users access the same document simultaneously THEN the RAG cache SHALL use thread-safe data structures (e.g., ConcurrentHashMap) to prevent race conditions

**3. URL Encoding and File Path**

2.7 WHEN a filename contains spaces or special characters THEN the system SHALL properly encode the filename only once at the point of HTTP request, not in the URL string itself

2.8 WHEN the backend sends a file path to AI Engine THEN the system SHALL send the raw Supabase Storage URL without pre-encoding, allowing the HTTP client to handle encoding automatically

2.9 WHEN the AI Engine downloads a remote file from Supabase Storage THEN the system SHALL use `urllib.request.urlretrieve()` with proper URL encoding or handle encoded URLs correctly

**4. Authentication and CORS**

2.10 WHEN a request is made to `/ai/**` endpoints THEN the JWT filter SHALL explicitly skip these endpoints before attempting authentication, ensuring permitAll() works correctly

2.11 WHEN the frontend makes cross-origin requests THEN the backend SHALL return proper CORS headers including `Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, and `Access-Control-Allow-Headers`

**5. Error Handling and Logging**

2.12 WHEN an error occurs in the AI Engine THEN the system SHALL return structured error responses with error codes, messages, and details for proper backend handling

2.13 WHEN JSON parsing fails in the backend THEN the error message SHALL include the first 200 characters of the actual response received for debugging

2.14 WHEN the AI Engine returns an error in streaming format THEN the system SHALL prefix error messages with a recognizable marker (e.g., "ERROR:") that the backend can detect

### Unchanged Behavior (Regression Prevention)

**1. Working Features Must Continue**

3.1 WHEN a user authenticates with valid Supabase JWT THEN the system SHALL CONTINUE TO allow access to protected endpoints

3.2 WHEN a user uploads a document to Supabase Storage THEN the system SHALL CONTINUE TO save the document metadata to the database and return the public URL

3.3 WHEN a user uses Global AI chat without documents THEN the system SHALL CONTINUE TO return responses correctly using the `/global-ai` endpoint

3.4 WHEN a user accesses the health check endpoint THEN the system SHALL CONTINUE TO return `{"status": "healthy"}` without authentication

**2. Database Operations**

3.5 WHEN chat messages are saved to the database THEN the system SHALL CONTINUE TO store user and AI messages with correct timestamps and session associations

3.6 WHEN chat history is retrieved THEN the system SHALL CONTINUE TO return messages in chronological order grouped by session

**3. Frontend Functionality**

3.7 WHEN a user navigates between pages (login, signup, dashboard, global chat, secret chat) THEN the system SHALL CONTINUE TO load pages correctly with proper authentication checks

3.8 WHEN a user selects a document from the dropdown THEN the system SHALL CONTINUE TO display the document name and allow question submission

**4. AI Engine Core Features**

3.9 WHEN the AI Engine receives a valid question for Global AI THEN the system SHALL CONTINUE TO generate responses using the Groq API with conversation history

3.10 WHEN the AI Engine creates a vector store for a document THEN the system SHALL CONTINUE TO cache the vector store for subsequent questions about the same document

**5. Security and Configuration**

3.11 WHEN environment variables are loaded (DATABASE_URL, SUPABASE_URL, GROQ_API_KEY) THEN the system SHALL CONTINUE TO use these values correctly with proper encoding

3.12 WHEN CORS middleware is configured THEN the system SHALL CONTINUE TO allow requests from all origins during development/testing
