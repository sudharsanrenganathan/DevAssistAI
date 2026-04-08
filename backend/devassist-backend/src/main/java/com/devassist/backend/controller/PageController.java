package com.devassist.backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    // =========================
    // ROOT → LOGIN
    // =========================
    @GetMapping("/")
    public String home() {
        return "redirect:/login.html";
    }

    // =========================
    // /dashboard → dashboard.html (static)
    // =========================
    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/dashboard.html";
    }

    // =========================
    // /global → global-ai.html (static)
    // =========================
    @GetMapping("/global")
    public String globalAI() {
        return "redirect:/global-ai.html";
    }

    // =========================
    // /secret → secret.html (static)
    // =========================
    @GetMapping("/secret")
    public String secretAI() {
        return "redirect:/secret.html";
    }

    // =========================
    // /analytics → analytics.html (static)
    // =========================
    @GetMapping("/analytics")
    public String analytics() {
        return "redirect:/analytics.html";
    }

    // =========================
    // /code → code.html (static)
    // =========================
    @GetMapping("/code")
    public String codeAdvisor() {
        return "redirect:/code.html";
    }
}