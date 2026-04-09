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
    public ResponseEntity<?> createChat(@RequestBody Map<String, String> body) {
        try {
            String firstMessage = body.get("message");
            String supabaseUserId = body.get("userId");

            if (supabaseUserId == null || supabaseUserId.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
            }

            GlobalChatThread chat = new GlobalChatThread();
            chat.setSupabaseUserId(supabaseUserId);
            chat.setTitle(generateTitle(firstMessage));
            GlobalChatThread saved = chatRepository.save(chat);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            System.out.println("❌ createChat error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to create chat: " + e.getMessage()));
        }
    }

    // ✅ PIN / UNPIN
    @PutMapping("/{chatId}/pin")
    public ResponseEntity<?> pinChat(@PathVariable Long chatId) {
        try {
            GlobalChatThread chat = chatRepository.findById(chatId)
                    .orElseThrow(() -> new RuntimeException("Chat not found: " + chatId));
            if (chat.isPinned()) {
                chat.setPinned(false);
                chat.setPinOrder(0L);
            } else {
                chat.setPinned(true);
                chat.setPinOrder(System.currentTimeMillis());
            }
            return ResponseEntity.ok(chatRepository.saveAndFlush(chat));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
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
    public ResponseEntity<?> getChat(@PathVariable Long chatId) {
        try {
            List<GlobalMessage> messages = globalMessageRepository.findByChatIdOrderByTimestampAsc(chatId);
            return ResponseEntity.ok(messages);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ LOAD ALL CHATS — filtered by user
    @GetMapping("/all")
    public ResponseEntity<?> getAllChats(@RequestParam(required = false) String userId) {
        try {
            Sort sort = Sort.by(
                Sort.Order.desc("isPinned"),
                Sort.Order.desc("pinOrder"),
                Sort.Order.desc("createdAt")
            );

            if (userId != null && !userId.isEmpty()) {
                List<GlobalChatThread> chats = chatRepository.findBySupabaseUserId(userId, sort);
                return ResponseEntity.ok(chats);
            }
            return ResponseEntity.ok(List.of());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ DELETE CHAT
    @DeleteMapping("/{chatId}")
    public ResponseEntity<?> deleteChat(@PathVariable Long chatId) {
        try {
            chatRepository.deleteById(chatId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
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