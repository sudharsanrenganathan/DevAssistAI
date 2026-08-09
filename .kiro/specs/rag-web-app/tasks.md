# Implementation Plan: RAG Web Application

## Overview

This implementation plan breaks down the RAG Web Application into discrete, actionable coding tasks. The application will be built as a standalone project within the `RAG/` folder using Python (FastAPI) for the backend and React (Vite + Tailwind) for the frontend. All tasks build incrementally, with checkpoints to ensure stability before proceeding.

**Technology Stack:**
- **Backend**: Python, FastAPI, LangChain, FAISS, sentence-transformers, pypdf, python-docx
- **Frontend**: React, Vite, Tailwind CSS, Axios
- **LLM**: OpenAI or Ollama

## Tasks

### Phase 1: Project Setup and Structure

- [x] 1. Create project structure and initialize backend
  - Create `RAG/` folder at workspace root
  - Create backend directory structure: `backend/api/`, `backend/core/`, `backend/utils/`, `backend/uploads/`, `backend/vector_db/`, `backend/metadata/`
  - Create `__init__.py` files in all Python packages
  - Create `requirements.txt` with dependencies: fastapi, uvicorn, langchain, faiss-cpu, sentence-transformers, pypdf, python-docx, python-multipart, pydantic, python-dotenv
  - Create `.env.example` with environment variable templates
  - Create `.gitignore` to exclude uploads/, vector_db/, metadata/, .env, venv/
  - _Requirements: 9.1, 9.2, 9.3, 9.4_

- [x] 2. Initialize frontend project with Vite and Tailwind
  - Create `frontend/` directory in RAG folder
  - Initialize Vite React project: `npm create vite@latest frontend -- --template react`
  - Install dependencies: `npm install axios tailwindcss postcss autoprefixer`
  - Initialize Tailwind CSS: `npx tailwindcss init -p`
  - Configure Tailwind in `tailwind.config.js` with content paths
  - Create `frontend/.env.example` with `VITE_API_BASE_URL` template
  - Create frontend directory structure: `src/components/`, `src/services/`
  - _Requirements: 9.1, 9.3, 9.4, 10.1_

- [x] 3. Create README.md with setup instructions
  - Document project overview and architecture
  - Add backend setup instructions (venv, pip install, .env configuration)
  - Add frontend setup instructions (npm install, .env configuration)
  - Add local development instructions (running backend and frontend)
  - Add deployment considerations
  - _Requirements: 9.5_

### Phase 2: Backend Core - File Management

- [x] 4. Implement file validation and upload handling
  - [x] 4.1 Create `backend/utils/file_handler.py` with file validation functions
    - Implement `validate_file(file: UploadFile) -> tuple[bool, str]` to check file type (PDF, TXT, DOCX) and size (<50MB)
    - Implement `sanitize_filename(filename: str) -> str` to remove special characters and prevent path traversal
    - Implement `generate_file_id() -> str` to create unique UUID for files
    - _Requirements: 1.4, 1.5, 8.3_
  
  - [x] 4.2 Implement file storage and metadata management
    - Implement `async save_uploaded_file(file: UploadFile) -> dict` to save file to uploads/ directory with unique ID
    - Implement `save_metadata(file_id: str, metadata: dict) -> None` to store file metadata as JSON in metadata/ directory
    - Implement `load_metadata(file_id: str) -> dict` to read metadata from JSON file
    - Implement `list_all_files() -> list[dict]` to return all file metadata
    - _Requirements: 1.1, 1.3, 3.1_

### Phase 3: Backend Core - Document Processing

- [x] 5. Implement text extraction from documents
  - [x] 5.1 Create `backend/core/document_processor.py` with text extraction
    - Implement `extract_text_from_pdf(file_path: str) -> str` using pypdf
    - Implement `extract_text_from_txt(file_path: str) -> str` with UTF-8 encoding
    - Implement `extract_text_from_docx(file_path: str) -> str` using python-docx
    - Implement `extract_text(file_path: str, file_type: str) -> str` dispatcher function
    - Add error handling for corrupted files and empty documents
    - _Requirements: 2.1, 2.6, 2.7_
  
  - [x] 5.2 Implement text chunking with overlap
    - Implement `chunk_text(text: str, chunk_size: int = 500, overlap: int = 100) -> list[str]` using LangChain's RecursiveCharacterTextSplitter
    - Configure chunking parameters: 500-1000 token chunks with 100 token overlap
    - Handle edge cases: empty text, text shorter than chunk size
    - _Requirements: 2.2_
  
  - [x] 5.3 Implement embedding generation
    - Implement `generate_embeddings(chunks: list[str], model_name: str = "all-MiniLM-L6-v2") -> np.ndarray` using sentence-transformers
    - Load embedding model once at module initialization for performance
    - Return embedding matrix with shape [n_chunks, embedding_dim]
    - _Requirements: 2.3_

### Phase 4: Backend Core - Vector Store

- [x] 6. Implement FAISS vector store operations
  - [x] 6.1 Create `backend/core/vector_store.py` with FAISS index management
    - Implement `create_vector_index(embeddings: np.ndarray, chunks: list[str], file_id: str) -> str` to create FAISS IndexFlatL2 and save to vector_db/
    - Implement `save_index(index, chunks: list[str], file_id: str) -> str` to persist FAISS index and chunks as JSON
    - Implement `load_vector_index(file_id: str) -> tuple` to load FAISS index and chunks from disk
    - _Requirements: 2.4_
  
  - [x] 6.2 Implement similarity search
    - Implement `search_similar_chunks(query: str, file_id: str, k: int = 4) -> list[dict]` to retrieve top-k similar chunks
    - Generate query embedding using same model as document embeddings
    - Return list of dictionaries with chunk_text, score, and index
    - Handle case where file_id doesn't exist
    - _Requirements: 4.2_

### Phase 5: Backend Core - Query Engine

- [x] 7. Implement RAG pipeline and LLM integration
  - [x] 7.1 Create `backend/core/query_engine.py` with context retrieval
    - Implement `retrieve_context(question: str, file_id: str, k: int = 4) -> tuple[str, list[dict]]` to get relevant chunks
    - Format retrieved chunks into context string
    - Return context and source chunks
    - _Requirements: 4.2, 4.3_
  
  - [x] 7.2 Implement prompt construction with strict system instructions
    - Implement `construct_prompt(question: str, context: str) -> tuple[str, str]` to build system and user prompts
    - Use system prompt: "You are an AI assistant that answers questions ONLY from the provided context. If the answer is not present in the context, say: 'The answer is not found in the uploaded file. Please contact your coordinator for better accuracy.' Do not add extra information. Do not guess."
    - Format user prompt with context and question
    - _Requirements: 4.6_
  
  - [x] 7.3 Implement LLM API calls (OpenAI and Ollama)
    - Implement `async call_openai(system_prompt: str, user_prompt: str, model: str) -> str` using OpenAI API
    - Implement `async call_ollama(system_prompt: str, user_prompt: str, model: str) -> str` using Ollama API
    - Implement `async call_llm(system_prompt: str, user_prompt: str, model_name: str = None) -> str` dispatcher based on configuration
    - Add error handling for API failures and timeouts
    - _Requirements: 4.4, 11.1, 11.2_
  
  - [x] 7.4 Implement complete RAG pipeline with fallback logic
    - Implement `async generate_answer(question: str, file_id: str, model_name: str = None) -> dict` orchestrating full pipeline
    - Check if retrieved chunks are relevant (similarity threshold)
    - Return fallback message if no relevant chunks found
    - Return answer with sources and fallback_used flag
    - _Requirements: 4.5, 4.6_

- [x] 8. Implement document processing pipeline orchestration
  - Create `async process_document(file_id: str, file_path: str, file_type: str) -> dict` in document_processor.py
  - Orchestrate: text extraction → chunking → embedding generation → FAISS index creation
  - Update metadata with processing status, chunk_count, and vector_index_path
  - Handle errors and update metadata with error_message
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

### Phase 6: Backend API

- [x] 9. Implement FastAPI application and routes
  - [x] 9.1 Create `backend/api/main.py` with FastAPI app initialization
    - Initialize FastAPI app with CORS middleware
    - Configure CORS to allow frontend origins from environment variable
    - Add startup event to load embedding model
    - Add health check endpoint GET /api/health
    - _Requirements: 7.1, 7.2, 7.3_
  
  - [x] 9.2 Create `backend/api/routes.py` with file upload endpoint
    - Implement POST /api/upload endpoint accepting multipart file upload
    - Validate file using file_handler.validate_file()
    - Save file using file_handler.save_uploaded_file()
    - Trigger async document processing
    - Return JSON with file_id, filename, and processing status
    - Handle errors: unsupported format (400), file too large (400), server error (500)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 7.1, 7.4_
  
  - [x] 9.3 Implement file listing endpoint
    - Implement GET /api/files endpoint
    - Call file_handler.list_all_files() to get all file metadata
    - Return JSON with list of files including file_id, filename, upload_time, status, chunk_count
    - _Requirements: 3.1, 7.2_
  
  - [x] 9.4 Implement query endpoint with validation
    - Create Pydantic model QueryRequest with question, file_id, optional model
    - Implement POST /api/query endpoint accepting QueryRequest JSON
    - Validate file_id exists and document is processed
    - Call query_engine.generate_answer() with question and file_id
    - Return JSON with answer, sources, and fallback_used flag
    - Handle errors: no file selected (400), file not found (404), document not processed (400), LLM unavailable (500)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 7.3, 7.5, 8.1, 8.4, 8.5, 8.6_

- [x] 10. Checkpoint - Test backend API with curl or Postman
  - Ensure all tests pass, ask the user if questions arise.

### Phase 7: Frontend Components

- [x] 11. Implement API service layer
  - Create `frontend/src/services/api.js` with Axios client
  - Configure base URL from environment variable VITE_API_BASE_URL
  - Implement `uploadFile(file: File, onProgress: (progress: number) => void) -> Promise` for POST /api/upload
  - Implement `fetchFiles() -> Promise` for GET /api/files
  - Implement `queryDocument(question: string, fileId: string, model?: string) -> Promise` for POST /api/query
  - Add error handling for network errors and API errors
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 12. Implement FileUpload component
  - [x] 12.1 Create `frontend/src/components/FileUpload.jsx` with file selection UI
    - Create component with file input button and drag-and-drop zone
    - Implement state: uploading, progress, error, dragActive
    - Implement handleFileSelect to validate file type and size
    - Display supported formats: PDF, TXT, DOCX (max 50MB)
    - _Requirements: 1.1, 1.4, 1.5, 10.3_
  
  - [x] 12.2 Implement upload progress and feedback
    - Call api.uploadFile() with progress callback
    - Display progress bar during upload
    - Show success message on completion and call onUploadComplete callback
    - Show error message on failure
    - Implement drag-and-drop handlers: handleDragOver, handleDrop
    - _Requirements: 1.2, 5.4, 6.1_

- [x] 13. Implement FileList component
  - [x] 13.1 Create `frontend/src/components/FileList.jsx` with file display
    - Create component with scrollable file list
    - Implement state: files, loading, selectedFileId, error
    - Call api.fetchFiles() on component mount
    - Display each file with filename, upload timestamp, and status indicator
    - Show empty state message when no files uploaded
    - _Requirements: 3.1, 3.4, 10.3_
  
  - [x] 13.2 Implement file selection and highlighting
    - Implement handleFileClick to update selectedFileId
    - Highlight selected file with blue background
    - Call onFileSelect callback when file is clicked
    - Add refresh button to reload file list
    - _Requirements: 3.2, 3.3_

- [x] 14. Implement ChatInterface component
  - [x] 14.1 Create `frontend/src/components/ChatInterface.jsx` with message display
    - Create component with message list and input box
    - Implement state: messages, inputValue, loading, error
    - Display user messages (right-aligned, blue background)
    - Display AI messages (left-aligned, gray background)
    - Implement auto-scroll to bottom when new messages added
    - _Requirements: 5.1, 5.6, 5.7, 10.5_
  
  - [x] 14.2 Implement question input and submission
    - Create textarea input with auto-resize
    - Implement handleInputChange to update inputValue
    - Disable submit button when input is empty or no file selected
    - Implement handleSubmit to call api.queryDocument()
    - Add user message to chat immediately
    - Display loading indicator ("Generating answer...") while waiting
    - Add AI response to chat when received
    - Handle errors and display error messages
    - _Requirements: 4.1, 4.7, 5.2, 5.3, 5.4, 5.5, 6.3, 8.1, 8.2_

- [x] 15. Implement main App component and layout
  - Create `frontend/src/App.jsx` with layout structure
  - Implement state: selectedFileId
  - Create sidebar with FileUpload and FileList components
  - Create main area with ChatInterface component
  - Pass selectedFileId to ChatInterface
  - Pass onFileSelect callback to FileList
  - Pass onUploadComplete callback to FileUpload to refresh file list
  - Apply Tailwind CSS styling for responsive layout
  - _Requirements: 10.2, 10.3, 10.4, 10.5, 10.6_

- [x] 16. Style components with Tailwind CSS
  - Apply Tailwind classes to all components for consistent styling
  - Implement responsive design for desktop and tablet
  - Add hover states, focus states, and transitions
  - Style loading indicators and error messages
  - Ensure accessibility (ARIA labels, keyboard navigation)
  - _Requirements: 10.1, 10.2, 10.6_

### Phase 8: Integration and Testing

- [x] 17. Integrate frontend and backend
  - [x] 17.1 Configure CORS and environment variables
    - Set CORS_ORIGINS in backend .env to include frontend URL
    - Set VITE_API_BASE_URL in frontend .env to backend URL
    - Test cross-origin requests work correctly
    - _Requirements: 7.1, 7.2, 7.3_
  
  - [x] 17.2 Test complete upload and query flow
    - Upload PDF file and verify processing completes
    - Select file and ask question with answer in document
    - Verify answer is displayed correctly
    - Test fallback message with question not in document
    - _Requirements: 1.1, 2.1, 2.2, 2.3, 2.4, 3.2, 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ]* 18. Write backend unit tests
  - [ ]* 18.1 Write tests for file_handler.py
    - Test validate_file with valid and invalid file types
    - Test validate_file with file size limits
    - Test sanitize_filename with special characters
    - Test save_uploaded_file and load_metadata
    - Test list_all_files with multiple files
    - _Requirements: 1.4, 1.5_
  
  - [ ]* 18.2 Write tests for document_processor.py
    - Test extract_text with sample PDF, TXT, DOCX files
    - Test chunk_text with various text lengths
    - Test generate_embeddings returns correct shape
    - Test process_document end-to-end with mock files
    - _Requirements: 2.1, 2.2, 2.3_
  
  - [ ]* 18.3 Write tests for vector_store.py
    - Test create_vector_index and save_index
    - Test load_vector_index with existing index
    - Test search_similar_chunks with known query
    - Test error handling for non-existent file_id
    - _Requirements: 2.4, 4.2_
  
  - [ ]* 18.4 Write tests for query_engine.py
    - Test retrieve_context returns relevant chunks
    - Test construct_prompt formats correctly
    - Mock LLM API calls and test call_llm
    - Test generate_answer with fallback logic
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6_

- [ ]* 19. Write frontend unit tests
  - [ ]* 19.1 Write tests for FileUpload component
    - Test file selection triggers upload
    - Test file validation (type and size)
    - Test upload progress display
    - Test success and error callbacks
    - _Requirements: 1.1, 1.2, 1.4, 1.5_
  
  - [ ]* 19.2 Write tests for FileList component
    - Test file list rendering with mock data
    - Test empty state display
    - Test file selection highlighting
    - Test file click handler
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  
  - [ ]* 19.3 Write tests for ChatInterface component
    - Test message rendering (user and AI messages)
    - Test input validation (empty input disables submit)
    - Test submit handler with valid input
    - Test loading state during answer generation
    - Test error message display
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_

- [ ]* 20. Write integration tests
  - [ ]* 20.1 Write API integration tests
    - Test POST /api/upload with valid and invalid files
    - Test GET /api/files returns correct data
    - Test POST /api/query with valid and invalid requests
    - Test error responses and status codes
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
  
  - [ ]* 20.2 Write end-to-end workflow tests
    - Test complete upload → process → select → query → answer flow
    - Test fallback message when answer not in document
    - Test error handling for all error scenarios
    - _Requirements: 1.1, 2.1, 3.2, 4.1, 4.5, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

### Phase 9: Configuration and Documentation

- [x] 21. Configure environment variables and deployment
  - Create backend/.env.example with all required variables: OPENAI_API_KEY, DEFAULT_LLM_MODEL, EMBEDDING_MODEL, MAX_FILE_SIZE_MB, ALLOWED_FILE_TYPES, CORS_ORIGINS
  - Create frontend/.env.example with VITE_API_BASE_URL
  - Document environment variable configuration in README.md
  - Add deployment instructions for local development and production
  - _Requirements: 9.5, 11.4, 11.5_

- [x] 22. Final checkpoint - Manual testing and validation
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional testing tasks and can be skipped for faster MVP delivery
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation and allow for user feedback
- All implementation is contained within the `RAG/` folder for portability
- Backend uses Python with FastAPI, frontend uses React with Vite and Tailwind CSS
- The system uses file-based storage (no external database required)
- LLM responses are strictly constrained to document content with explicit fallback messages
