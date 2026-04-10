package com.devassist.backend.controller;

import com.devassist.backend.entity.Document;
import com.devassist.backend.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import com.devassist.backend.entity.*;
import com.devassist.backend.repository.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.devassist.backend.dto.QuestionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;


    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.key:}") // Allow empty for now, user needs to set this
    private String supabaseKey;

    @Value("${supabase.anon-key:}")
    private String supabaseAnonKey;

    @Value("${ai.engine.url:http://127.0.0.1:8000}")
    private String aiEngineUrl;

    @Autowired
    private RestTemplate restTemplate;

    // ================================================================
    // UPLOAD DOCUMENT — saves to disk + DB + forwards to FastAPI
    // ================================================================
    @PostMapping("/upload/{userId}")
    public ResponseEntity<?> uploadDocument(
            @PathVariable String userId,
            @RequestParam("file") MultipartFile file) {

        try {
            System.out.println("UPLOAD API HIT");
            System.out.println("Supabase URL: " + supabaseUrl);
            System.out.println("Using anon key for Storage upload");

            // --- Supabase Storage Upload ---
            String bucketName = "documents";
            String originalName = file.getOriginalFilename();
            if (originalName == null) originalName = "file.pdf";
            String fileName = System.currentTimeMillis() + "_" + originalName;

            // Supabase Storage API Endpoint
            String supabaseStorageUrl = supabaseUrl + "/storage/v1/object/" + bucketName + "/" + fileName;
            System.out.println("Upload URL: " + supabaseStorageUrl);

            HttpHeaders headers = new HttpHeaders();
            // Use anon key for public bucket access
            headers.set("Authorization", "Bearer " + supabaseAnonKey);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set("x-upsert", "true");

            HttpEntity<byte[]> requestEntity = new HttpEntity<>(file.getBytes(), headers);

            try {
                restTemplate.postForObject(supabaseStorageUrl, requestEntity, String.class);
                System.out.println("✅ File uploaded to Supabase Storage: " + fileName);
            } catch (Exception e) {
                System.out.println("❌ Supabase Storage upload error: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Supabase Storage upload failed: " + e.getMessage());
            }

            // Public URL for the stored document
            String publicUrl = supabaseUrl + "/storage/v1/object/public/" + bucketName + "/" + fileName;

            // Save to DB
            Document doc = new Document(
                    originalName,
                    file.getContentType(),
                    file.getSize(),
                    publicUrl,
                    userId
            );
            doc.setUploadedAt(LocalDateTime.now());
            documentRepository.save(doc);
            System.out.println("✅ Saved to DB");

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "File uploaded successfully",
                "fileName", fileName,
                "publicUrl", publicUrl,
                "document", doc
            ));

        } catch (Exception e) {
            System.out.println("❌ Upload failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", "Upload failed: " + e.getMessage()
            ));
        }
    }

    // ================================================================
    // DELETE DOCUMENT — removes from DB + deletes file from disk
    // ================================================================
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id) {
        try {
            Document doc = documentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            // Delete from Supabase Storage
            String bucketName = "documents";
            // Extract the fileName from the public URL or filePath stored in DB
            String fileName = doc.getFilePath().substring(doc.getFilePath().lastIndexOf("/") + 1);
            String supabaseStorageUrl = supabaseUrl + "/storage/v1/object/" + bucketName + "/" + fileName;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(supabaseKey);
            HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

            try {
                restTemplate.exchange(supabaseStorageUrl, org.springframework.http.HttpMethod.DELETE, requestEntity, String.class);
                System.out.println("File deleted from Supabase Storage: " + fileName);
            } catch (Exception e) {
                System.out.println("Supabase Storage delete failed (non-critical): " + e.getMessage());
            }

            // Delete from DB
            documentRepository.deleteById(id);
            System.out.println("Document deleted from DB: " + id);

            return ResponseEntity.ok("Deleted");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Delete failed: " + e.getMessage());
        }
    }

    // ================================================================
    // GET USER DOCUMENTS — fetch all docs for a user
    // ================================================================
    @GetMapping("/documents/{userId}")
    public List<Document> getUserDocuments(@PathVariable String userId) {
        return documentRepository.findByUserId(userId);
    }

    // ================================================================
    // RAG ASK — ask question about a specific document
    // ================================================================
    @PostMapping("/rag/{docId}")
    public Map<String, Object> askRag(
            @PathVariable Long docId,
            @RequestBody QuestionRequest request) {

        String question = request.getQuestion();
        if (question == null || question.isEmpty()) {
            throw new RuntimeException("Question cannot be empty");
        }

        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        String filePath = doc.getFilePath();
        if (filePath == null || filePath.isEmpty()) {
            throw new RuntimeException("Invalid file path");
        }

        // Send raw file path - let HTTP client handle encoding
        String aiUrl = aiEngineUrl + "/rag-ask";
        Map<String, Object> requestBody = Map.of(
                "question", question,
                "file_path", filePath,  // Raw path, no manual encoding
                "session_id", docId // Using docId as session_id for document-specific context
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            // Use exchange() to get full response with proper timeout handling
            ResponseEntity<String> response = restTemplate.exchange(
                aiUrl, 
                HttpMethod.POST, 
                entity, 
                String.class
            );
            
            String answer = response.getBody();
            
            if (answer == null || answer.isEmpty()) {
                throw new RuntimeException("AI returned empty response");
            }
            
            return Map.of("answer", answer);
        } catch (Exception e) {
            String errorMsg = "RAG request failed: " + e.getMessage();
            
            // Add detailed error logging with response preview
            if (e instanceof HttpClientErrorException) {
                HttpClientErrorException httpEx = (HttpClientErrorException) e;
                String responseBody = httpEx.getResponseBodyAsString();
                String preview = responseBody.length() > 200 
                    ? responseBody.substring(0, 200) + "..." 
                    : responseBody;
                errorMsg += " | Response: " + preview;
            }
            
            System.out.println("❌ " + errorMsg);
            e.printStackTrace();
            throw new RuntimeException(errorMsg);
        }
    }

    // ================================================================
    // GLOBAL AI ASK — ask without RAG
    // ================================================================
    @PostMapping("/ask/{userId}/{sessionId}")
    public Map<String, Object> askAI(
            @PathVariable String userId,
            @PathVariable Long sessionId,
            @RequestBody QuestionRequest request) {

        String question = request.getQuestion();
        if (question == null || question.isEmpty()) {
            throw new RuntimeException("Question cannot be empty");
        }

        String aiUrl = aiEngineUrl + "/global-ai";
        Map<String, Object> requestBody = Map.of(
                "question", question, 
                "has_file", false,
                "session_id", sessionId
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        Map<String, Object> response;
        try {
            response = restTemplate.postForObject(aiUrl, entity, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("FastAPI server not running");
        }

        if (response == null || response.get("answer") == null) {
            throw new RuntimeException("AI server not responding");
        }

        String answer = response.get("answer").toString();

        // Save chat to DB
        ChatSession session = sessionRepository.findById(sessionId).orElseThrow();

        if (session.getTitle().equals("New Chat")) {
            String title = question.length() > 30 ? question.substring(0, 30) + "..." : question;
            session.setTitle(title);
            sessionRepository.save(session);
        }

        Chat chat = new Chat();
        chat.setUserId(userId);
        chat.setSession(session);
        chat.setQuestion(question);
        chat.setAnswer(answer);
        chat.setTimestamp(LocalDateTime.now());
        chatRepository.save(chat);

        return response;
    }

    // ================================================================
    // SESSION MANAGEMENT
    // ================================================================
    @PostMapping("/session/{userId}")
    public ChatSession createSession(@PathVariable String userId) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle("New Chat");
        session.setCreatedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    @GetMapping("/sessions/{userId}")
    public List<ChatSession> getSessions(@PathVariable String userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @GetMapping("/messages/{sessionId}")
    public List<Chat> getMessages(@PathVariable Long sessionId) {
        ChatSession session = sessionRepository.findById(sessionId).orElseThrow();
        return chatRepository.findBySessionOrderByTimestampAsc(session);
    }

    @DeleteMapping("/session/{sessionId}")
    public String deleteSession(@PathVariable Long sessionId) {
        sessionRepository.deleteById(sessionId);
        return "Deleted";
    }

    @PutMapping("/session/{sessionId}")
    public ChatSession renameSession(
            @PathVariable Long sessionId,
            @RequestParam String title) {
        ChatSession session = sessionRepository.findById(sessionId).orElseThrow();
        session.setTitle(title);
        return sessionRepository.save(session);
    }
}