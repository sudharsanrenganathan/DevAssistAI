package com.devassist.backend.service;

import com.devassist.backend.entity.*;
import com.devassist.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class VmDataLoader implements CommandLineRunner {

    @Autowired private VmProblemRepository problemRepo;
    @Autowired private VmSubmissionRepository submissionRepo;
    @Autowired private VmLeaderboardRepository leaderboardRepo;

    @Override
    public void run(String... args) {
        loadQuestions();
        System.out.println("✅ VoidMain data loaded");
    }

    private void loadQuestions() {
        String[] folders = {"easy", "medium", "hard"};

        for (String folder : folders) {
            try {
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource[] resources = resolver.getResources("classpath:questions/" + folder + "/*.txt");

                for (Resource resource : resources) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                        String fullContent = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                        // Re-read for parsing
                        List<String> lines;
                        try (BufferedReader br2 = new BufferedReader(
                                new InputStreamReader(new ClassPathResource(
                                    "questions/" + folder + "/" + resource.getFilename()).getInputStream(),
                                    StandardCharsets.UTF_8))) {
                            lines = br2.lines().toList();
                        }

                        String id = "", title = "", difficulty = "";
                        StringBuilder description = new StringBuilder();
                        boolean inDesc = false;

                        for (String line : lines) {
                            String trimmed = line.trim();

                            if (trimmed.startsWith("ID:")) {
                                id = trimmed.substring(3).trim();
                                inDesc = false;
                            } else if (trimmed.startsWith("TITLE:")) {
                                title = trimmed.substring(6).trim();
                                inDesc = false;
                            } else if (trimmed.startsWith("DIFFICULTY:")) {
                                difficulty = trimmed.substring(11).trim();
                                inDesc = false;
                            } else if (trimmed.equals("DESCRIPTION:")) {
                                inDesc = true;
                            } else if (trimmed.equals("INPUT FORMAT:") || trimmed.equals("SAMPLE TEST CASES:")) {
                                inDesc = false;
                            } else if (inDesc && !trimmed.isEmpty()) {
                                if (description.length() > 0) description.append(" ");
                                description.append(trimmed);
                            }
                        }

                        if (!id.isEmpty() && !problemRepo.existsById(id)) {
                            VmProblem problem = new VmProblem(id, title, difficulty,
                                    description.toString(), fullContent);
                            problemRepo.save(problem);
                        }

                    } catch (Exception e) {
                        System.out.println("⚠ Error loading question: " + resource.getFilename() + " - " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠ Error scanning folder: " + folder + " - " + e.getMessage());
            }
        }
    }
}
