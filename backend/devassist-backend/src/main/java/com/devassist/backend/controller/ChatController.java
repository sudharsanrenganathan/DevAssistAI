package com.devassist.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import com.devassist.backend.entity.SecretMessage;
import com.devassist.backend.entity.ChatThread;
import com.devassist.backend.repository.SecretMessageRepository;
import com.devassist.backend.repository.ChatThreadRepository;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin("*")
public class ChatController {

    @Autowired
    private ChatThreadRepository chatRepository;

    @Autowired
    private SecretMessageRepository secretMessageRepository;

    // ✅ CREATE CHAT THREAD — scoped to user
    @PostMapping("/create")
    public ChatThread createChat(@RequestBody Map<String, String> body) {
        String firstMessage = body.get("message");
        String supabaseUserId = body.get("userId");

        ChatThread chat = new ChatThread();
        chat.setSupabaseUserId(supabaseUserId);
        chat.setTitle(generateTitle(firstMessage));
        return chatRepository.save(chat);
    }

    // ✅ PIN / UNPIN CHAT
    @PutMapping("/{chatId}/pin")
    public ChatThread pinChat(@PathVariable Long chatId) {
        ChatThread chat = chatRepository.findById(chatId).orElseThrow();
        if (chat.isPinned()) {
            chat.setPinned(false);
            chat.setPinOrder(0L);
        } else {
            chat.setPinned(true);
            chat.setPinOrder(System.currentTimeMillis());
        }
        return chatRepository.saveAndFlush(chat);
    }

    // ✅ RENAME CHAT
    @PutMapping("/{chatId}/rename")
    public ResponseEntity<?> renameChat(@PathVariable Long chatId,
                                        @RequestBody Map<String, String> body) {
        ChatThread chat = chatRepository.findById(chatId).orElseThrow();
        String newTitle = body.get("title");
        if (newTitle != null && !newTitle.trim().isEmpty()) {
            chat.setTitle(newTitle.trim());
        }
        chatRepository.save(chat);
        return ResponseEntity.ok().build();
    }

    // 🔥 TITLE GENERATOR
    private String generateTitle(String message) {
        if (message == null || message.isEmpty()) return "New Chat";
        message = message.trim();
        return message.length() <= 25 ? message : message.substring(0, 25) + "...";
    }

    // ✅ SAVE MESSAGE — uses secret_messages table
    @PostMapping("/{chatId}/message")
    public ResponseEntity<?> saveMessage(@PathVariable Long chatId,
                              @RequestBody Map<String, String> body) {
        try {
            SecretMessage msg = new SecretMessage();
            msg.setChatId(chatId);
            msg.setRole(body.get("role"));
            msg.setContent(body.get("content"));
            return ResponseEntity.ok(secretMessageRepository.save(msg));
        } catch (Exception e) {
            System.out.println("❌ saveMessage error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body("Save failed: " + e.getMessage());
        }
    }

    // ✅ LOAD MESSAGES — from secret_messages table
    @GetMapping("/{chatId}")
    public List<SecretMessage> getChat(@PathVariable Long chatId) {
        return secretMessageRepository.findByChatIdOrderByTimestampAsc(chatId);
    }

    // ✅ LOAD ALL CHATS — filtered by user
    @GetMapping("/all")
    public List<ChatThread> getAllChats(@RequestParam(required = false) String userId) {
        Sort sort = Sort.by(
            Sort.Order.desc("isPinned"),
            Sort.Order.desc("pinOrder"),
            Sort.Order.desc("createdAt")
        );

        if (userId != null && !userId.isEmpty()) {
            return chatRepository.findBySupabaseUserId(userId, sort);
        }
        return List.of();
    }

    // ✅ DELETE CHAT
    @DeleteMapping("/{chatId}")
    public ResponseEntity<?> deleteChat(@PathVariable Long chatId) {
        chatRepository.deleteById(chatId);
        return ResponseEntity.ok().build();
    }
}