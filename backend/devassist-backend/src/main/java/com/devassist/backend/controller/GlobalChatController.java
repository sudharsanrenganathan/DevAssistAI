package com.devassist.backend.controller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import com.devassist.backend.entity.GlobalChatThread;
import com.devassist.backend.entity.GlobalMessage;
import com.devassist.backend.repository.GlobalChatThreadRepository;
import com.devassist.backend.repository.GlobalMessageRepository;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/global-chat")
public class GlobalChatController {

    @Autowired
    private GlobalChatThreadRepository chatRepository;

    @Autowired
    private GlobalMessageRepository globalMessageRepository;

    @org.springframework.beans.factory.annotation.Value("${ai.engine.url:http://127.0.0.1:8000}")
    private String aiEngineUrl;

    // ✅ CREATE CHAT — scoped to user
    @PostMapping("/create")
    public GlobalChatThread createChat(@RequestBody Map<String, String> body) {
        String firstMessage = body.get("message");
        String supabaseUserId = body.get("userId");

        GlobalChatThread chat = new GlobalChatThread();
        chat.setSupabaseUserId(supabaseUserId);
        chat.setTitle(generateTitle(firstMessage));
        return chatRepository.save(chat);
    }

    // ✅ PIN / UNPIN
    @PutMapping("/{chatId}/pin")
    public GlobalChatThread pinChat(@PathVariable Long chatId) {
        GlobalChatThread chat = chatRepository.findById(chatId).orElseThrow();
        if (chat.isPinned()) {
            chat.setPinned(false);
            chat.setPinOrder(0L);
        } else {
            chat.setPinned(true);
            chat.setPinOrder(System.currentTimeMillis());
        }
        return chatRepository.saveAndFlush(chat);
    }

    // ✅ RENAME
    @PutMapping("/{chatId}/rename")
    public ResponseEntity<?> renameChat(@PathVariable Long chatId,
                                        @RequestBody Map<String, String> body) {
        GlobalChatThread chat = chatRepository.findById(chatId).orElseThrow();
        String newTitle = body.get("title");
        if (newTitle != null && !newTitle.trim().isEmpty()) {
            chat.setTitle(newTitle.trim());
        }
        chatRepository.save(chat);
        return ResponseEntity.ok().build();
    }

    // ✅ SAVE MESSAGE
    @PostMapping("/{chatId}/message")
    public ResponseEntity<?> saveMessage(@PathVariable Long chatId,
                               @RequestBody Map<String, String> body) {
        try {
            GlobalChatThread chat = chatRepository.findById(chatId)
                    .orElseThrow(() -> new RuntimeException("Global chat not found: " + chatId));

            GlobalMessage msg = new GlobalMessage();
            msg.setChatId(chatId);
            msg.setRole(body.get("role"));
            msg.setContent(body.get("content"));
            return ResponseEntity.ok(globalMessageRepository.save(msg));
        } catch (Exception e) {
            System.out.println("❌ saveMessage error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    // ✅ LOAD MESSAGES
    @GetMapping("/{chatId}")
    public List<GlobalMessage> getChat(@PathVariable Long chatId) {
        return globalMessageRepository.findByChatIdOrderByTimestampAsc(chatId);
    }

    // ✅ LOAD ALL CHATS — filtered by user
    @GetMapping("/all")
    public List<GlobalChatThread> getAllChats(@RequestParam(required = false) String userId) {
        Sort sort = Sort.by(
            Sort.Order.desc("isPinned"),
            Sort.Order.desc("pinOrder"),
            Sort.Order.desc("createdAt")
        );

        if (userId != null && !userId.isEmpty()) {
            return chatRepository.findBySupabaseUserId(userId, sort);
        }
        // Fallback: return empty if no userId (shouldn't happen with auth)
        return List.of();
    }

    // ✅ DELETE CHAT
    @DeleteMapping("/{chatId}")
    public ResponseEntity<?> deleteChat(@PathVariable Long chatId) {
        chatRepository.deleteById(chatId);
        return ResponseEntity.ok().build();
    }

    // ✅ TITLE GENERATOR
    private String generateTitle(String message) {
        if (message == null || message.isEmpty()) return "New Chat";
        message = message.trim();
        return message.length() <= 25 ? message : message.substring(0, 25) + "...";
    }

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;

    @PostMapping("/ask")
    public ResponseEntity<?> askGlobalAI(@RequestBody Map<String, String> body) {
        try {
            String question = body.get("question");
            String sessionId = body.get("session_id");
            String model = body.get("model");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            
            java.util.HashMap<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("question", question);
            requestBody.put("has_file", false);
            if (sessionId != null) requestBody.put("session_id", sessionId);
            if (model != null) requestBody.put("model", model);
            
            HttpEntity<java.util.HashMap<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                aiEngineUrl + "/global-ai", entity, Map.class
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("answer", "AI request failed: " + e.getMessage()));
        }
    }

    @PostMapping("/local-ask")
    public ResponseEntity<?> askLocalAI(@RequestBody Map<String, String> body) {
        try {
            String question = body.get("question");
            String sessionId = body.get("session_id");
            String model = body.get("model");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            
            java.util.HashMap<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("question", question);
            requestBody.put("has_file", false);
            if (sessionId != null) requestBody.put("session_id", sessionId);
            if (model != null) requestBody.put("model", model);
            
            HttpEntity<java.util.HashMap<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                aiEngineUrl + "/local-ai", entity, Map.class
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("answer", "Local AI request failed: " + e.getMessage()));
        }
    }
}