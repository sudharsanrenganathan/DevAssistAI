package com.devassist.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.devassist.backend.entity.UserActivity;
import com.devassist.backend.repository.UserActivityRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/activity")
@CrossOrigin("*")
public class UserActivityController {

    @Autowired
    private UserActivityRepository activityRepository;

    // ✅ LOG ACTIVITY
    @PostMapping("/log")
    public UserActivity logActivity(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String toolName = body.get("toolName");
        String pageUrl = body.get("pageUrl");
        String icon = body.get("icon");
        
        if (userId == null || userId.isEmpty()) return null;

        // Create and save new activity
        UserActivity activity = new UserActivity();
        activity.setSupabaseUserId(userId);
        activity.setToolName(toolName);
        activity.setPageUrl(pageUrl);
        activity.setIcon(icon);
        activity.setTimestamp(LocalDateTime.now());
        
        return activityRepository.save(activity);
    }

    // ✅ GET RECENT ACTIVITY (TOP 15)
    @GetMapping("/recent/{userId}")
    public List<UserActivity> getRecentActivity(@PathVariable String userId) {
        if (userId == null || userId.isEmpty() || userId.equals("null")) return List.of();
        return activityRepository.findTop15BySupabaseUserIdOrderByTimestampDesc(userId);
    }
}
