package com.devassist.backend.controller;
import java.util.List;
import com.devassist.backend.entity.*;
import com.devassist.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/vm")
public class VoidMainController {

    @Autowired private VmProblemRepository problemRepo;
    @Autowired private VmSubmissionRepository submissionRepo;
    @Autowired private VmLeaderboardRepository leaderboardRepo;

    private final RestTemplate restTemplate = createRestTemplate();

private RestTemplate createRestTemplate() {
    RestTemplate rt = new RestTemplate();
    // Allow JSON deserialization even when content-type is octet-stream
    rt.getMessageConverters().add(0, new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter() {{
        setSupportedMediaTypes(List.of(
            MediaType.APPLICATION_JSON,
            MediaType.APPLICATION_OCTET_STREAM,
            MediaType.TEXT_PLAIN,
            MediaType.ALL
        ));
    }});
    return rt;
}
    private static final String WANDBOX_URL = "https://wandbox.org/api/compile.json";

    // Wandbox compiler IDs per language
    private String getCompilerId(String language) {
        if (language == null) language = "java";
        switch (language.toLowerCase()) {
            case "python":     return "cpython-3.12.7";
            case "c":          return "gcc-13.2.0-c";
            case "cpp":
            case "c++":        return "gcc-13.2.0";
            case "javascript":
            case "js":         return "nodejs-20.17.0";
            case "java":
            default:           return "openjdk-jdk-22+36";
        }
    }

    // ================================================================
    // GET /vm/problems — list all problems
    // ================================================================
    @GetMapping("/problems")
    public List<Map<String, Object>> getAllProblems(
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false, defaultValue = "guest") String username) {

        List<VmProblem> problems;
        if (difficulty != null && !difficulty.isEmpty()) {
            problems = problemRepo.findByDifficultyOrderByIdAsc(difficulty.toUpperCase());
        } else {
            problems = problemRepo.findAll();
            problems.sort(Comparator.comparing(VmProblem::getId));
        }

        // Get solved set for this user
        List<String> solvedIds = submissionRepo.findSolvedProblemIds(username);
        Set<String> solvedSet = new HashSet<>(solvedIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (VmProblem p : problems) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", p.getId());
            map.put("title", p.getTitle());
            map.put("difficulty", p.getDifficulty());
            map.put("solved", solvedSet.contains(p.getId()));
            result.add(map);
        }
        return result;
    }

    // ================================================================
    // GET /vm/problems/{id} — get one problem with full content
    // ================================================================
    @GetMapping("/problems/{id}")
    public ResponseEntity<?> getProblem(@PathVariable String id) {
        return problemRepo.findById(id.toUpperCase())
                .map(p -> ResponseEntity.ok(p))
                .orElse(ResponseEntity.notFound().build());
    }

    // ================================================================
    // POST /vm/run — run code against sample test cases
    // ================================================================
    @PostMapping("/run")
    public ResponseEntity<?> runCode(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "");
        String questionId = body.getOrDefault("questionId", "E001");
        String language = body.getOrDefault("language", "java");

        // Only do Java class renaming for Java
        if ("java".equalsIgnoreCase(language)) {
            code = code.replace("class Solution", "class Main");
        }

        try {
            List<TestCase> samples = loadTestCases(questionId, false);
            if (samples.isEmpty()) {
                return ResponseEntity.ok(Map.of("verdict", "Error", "error", "No sample test cases found for " + questionId));
            }

            int total = samples.size();
            int passed = 0;
            List<Map<String, Object>> results = new ArrayList<>();

            for (int i = 0; i < samples.size(); i++) {
                TestCase tc = samples.get(i);
                Map<String, Object> execResult = executeCode(code, tc.input, language);

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("sample", i + 1);
                r.put("input", tc.input != null ? tc.input.trim() : "");
                r.put("expected", tc.expected != null ? tc.expected.trim() : "");

                String status;
                if (execResult.containsKey("compile_error")) {
                    String compileErr = safeStr(execResult.get("compile_error"));
                    Map<String, Object> errResp = new LinkedHashMap<>();
                    errResp.put("verdict", "Compilation Error");
                    errResp.put("error", compileErr);
                    return ResponseEntity.ok(errResp);
                } else if (execResult.containsKey("runtime_error")) {
                    status = "RUNTIME_ERROR";
                    r.put("actual", "");
                    r.put("error", safeStr(execResult.get("runtime_error")));
                } else if (execResult.containsKey("timeout")) {
                    status = "TLE";
                    r.put("actual", "");
                } else {
                    String rawOutput = safeStr(execResult.get("output"));
                    String actual = normalize(rawOutput);
                    String expected = normalize(tc.expected);
                    r.put("actual", rawOutput.trim());
                    status = actual.equals(expected) ? "PASS" : "FAIL";
                }

                if (status.equals("PASS")) passed++;
                r.put("status", status);
                results.add(r);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("verdict", "Executed");
            response.put("total", total);
            response.put("passed", passed);
            response.put("failed", total - passed);
            response.put("results", results);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("verdict", "Error", "error", "Server error: " + e.getMessage()));
        }
    }

    // ================================================================
    // POST /vm/submit — judge against hidden test cases + save
    // ================================================================
    @PostMapping("/submit")
    public ResponseEntity<?> submitCode(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "");
        String questionId = body.getOrDefault("questionId", "E001");
        String username = body.getOrDefault("username", "guest");
        String language = body.getOrDefault("language", "java");

        // Only do Java class renaming for Java
        if ("java".equalsIgnoreCase(language)) {
            code = code.replace("class Solution", "class Main");
        }

        String verdict = "Accepted";
        int passed = 0;
        int total = 0;
        long startTime = System.currentTimeMillis();

        try {
            List<TestCase> hidden = loadTestCases(questionId, true);
            total = hidden.size();

            if (total == 0) {
                return ResponseEntity.ok(Map.of("verdict", "Error", "error", "No hidden test cases found"));
            }

            for (TestCase tc : hidden) {
                Map<String, Object> execResult = executeCode(code, tc.input, language);

                if (execResult.containsKey("compile_error")) {
                    verdict = "Compilation Error";
                    break;
                } else if (execResult.containsKey("runtime_error")) {
                    verdict = "Runtime Error";
                    break;
                } else if (execResult.containsKey("timeout")) {
                    verdict = "Time Limit Exceeded";
                    break;
                } else {
                    String actual = normalize(execResult.getOrDefault("output", "").toString());
                    String expected = normalize(tc.expected);
                    if (!actual.equals(expected)) {
                        verdict = "Wrong Answer";
                        break;
                    }
                    passed++;
                }
            }
        } catch (Exception e) {
            verdict = "Runtime Error";
        }

        long runtime = System.currentTimeMillis() - startTime;
        int failed = total - passed;

        // Save submission
        VmSubmission sub = new VmSubmission();
        sub.setUsername(username);
        sub.setProblemId(questionId);
        sub.setVerdict(verdict);
        sub.setPassed(passed);
        sub.setFailed(failed);
        sub.setExecutionTime(runtime);
        sub.setSubmittedAt(LocalDateTime.now());
        submissionRepo.save(sub);

        // Update leaderboard
        updateLeaderboard(username);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("verdict", verdict);
        response.put("total", total);
        response.put("passed", passed);
        response.put("failed", failed);
        response.put("runtime", runtime);
        return ResponseEntity.ok(response);
    }

    // ================================================================
    // GET /vm/submissions/{username}
    // ================================================================
    @GetMapping("/submissions/{username}")
    public List<VmSubmission> getSubmissions(@PathVariable String username) {
        return submissionRepo.findByUsernameOrderBySubmittedAtDesc(username);
    }

    // ================================================================
    // GET /vm/leaderboard
    // ================================================================
    @GetMapping("/leaderboard")
    public List<VmLeaderboard> getLeaderboard() {
        return leaderboardRepo.findTop50ByOrderByScoreDescSolvedDesc();
    }

    // ================================================================
    // GET /vm/stats/{username}
    // ================================================================
    @GetMapping("/stats/{username}")
    public Map<String, Object> getStats(@PathVariable String username) {
        List<String> solved = submissionRepo.findSolvedProblemIds(username);
        List<String> attempted = submissionRepo.findAttemptedProblemIds(username);

        int easySolved = 0, mediumSolved = 0, hardSolved = 0;
        for (String id : solved) {
            if (id.startsWith("E")) easySolved++;
            else if (id.startsWith("M")) mediumSolved++;
            else if (id.startsWith("H")) hardSolved++;
        }

        // Calculate score: easy=10, medium=20, hard=30
        int score = easySolved * 10 + mediumSolved * 20 + hardSolved * 30;

        List<VmSubmission> recent = submissionRepo.findByUsernameOrderBySubmittedAtDesc(username);
        int totalSubmissions = recent.size();
        long acceptedCount = recent.stream().filter(s -> "Accepted".equals(s.getVerdict())).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("solved", solved.size());
        stats.put("attempted", attempted.size());
        stats.put("score", score);
        stats.put("easySolved", easySolved);
        stats.put("mediumSolved", mediumSolved);
        stats.put("hardSolved", hardSolved);
        stats.put("totalSubmissions", totalSubmissions);
        stats.put("acceptedCount", acceptedCount);
        return stats;
    }
    // ================================================================
    // POST /vm/execute — arbitrary code execution (dry run)
    // ================================================================
    @PostMapping("/execute")
    public ResponseEntity<?> executeDryRun(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", "");
        String language = body.getOrDefault("language", "java");
        String input = body.getOrDefault("input", "");

        if ("java".equalsIgnoreCase(language)) {
            code = code.replace("class Solution", "class Main");
        }

        try {
            Map<String, Object> result = executeCode(code, input, language);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("runtime_error", "Server error: " + e.getMessage()));
        }
    }

    // ================================================================
    // WANDBOX API EXECUTION (Free, no API key required)
    // https://wandbox.org/
    // ================================================================
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeCode(String code, String input, String language) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (language == null || language.isEmpty()) language = "java";

        try {
            // Build Wandbox request body
            // API docs: https://github.com/melpon/wandbox/blob/master/kennel2/API.md
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("compiler", getCompilerId(language));
            requestBody.put("code", code);

            if (input != null && !input.trim().isEmpty()) {
                requestBody.put("stdin", input);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            Map<String, Object> response = restTemplate.postForObject(WANDBOX_URL, entity, Map.class);

            if (response == null) {
                result.put("runtime_error", "No response from Wandbox API");
                return result;
            }

            // Wandbox response structure:
            // { "status": "0", "compiler_output": "", "compiler_error": "",
            //   "program_output": "Hello", "program_error": "", "signal": "" }

            String status = safeStr(response.get("status"));
            String compilerError = safeStr(response.get("compiler_error"));
            String compilerOutput = safeStr(response.get("compiler_output"));
            String programOutput = safeStr(response.get("program_output"));
            String programError = safeStr(response.get("program_error"));
            String signal = safeStr(response.get("signal"));

            // Check for compilation errors
            if (!compilerError.isEmpty() && compilerError.contains("error")) {
                String errMsg = cleanErrorMessage(compilerError, language);
                result.put("compile_error", errMsg);
                return result;
            }

            // Check for timeout (signal = "Killed")
            if ("Killed".equalsIgnoreCase(signal) || "SIGKILL".equals(signal) ||
                programOutput.toLowerCase().contains("time limit")) {
                result.put("timeout", true);
                return result;
            }

            // Check for runtime errors (non-zero exit status)
            if (!"0".equals(status)) {
                String errMsg = !programError.isEmpty() ? programError : programOutput;
                if (errMsg.isEmpty()) errMsg = "Runtime error (exit code: " + status + ")";
                errMsg = cleanErrorMessage(errMsg, language);
                result.put("runtime_error", errMsg);
                return result;
            }

            // Check for runtime errors in stderr even with exit 0
            boolean hasRuntimeError = false;
            if ("java".equalsIgnoreCase(language)) {
                hasRuntimeError = !programError.isEmpty() &&
                    (programError.contains("Exception in thread") || programError.contains("java.lang."));
            } else if ("python".equalsIgnoreCase(language)) {
                hasRuntimeError = !programError.isEmpty() &&
                    (programError.contains("Traceback") || programError.contains("Error"));
            } else {
                hasRuntimeError = !programError.isEmpty() &&
                    (programError.contains("error") || programError.contains("fault"));
            }
            if (hasRuntimeError) {
                String errMsg = cleanErrorMessage(programError, language);
                result.put("runtime_error", errMsg);
                return result;
            }

            result.put("output", programOutput);

        } catch (Exception e) {
            result.put("runtime_error", "Code execution error: " + e.getMessage());
        }

        return result;
    }

    // Clean error messages based on language
    private String cleanErrorMessage(String msg, String language) {
        if (msg == null) return "";
        if ("java".equalsIgnoreCase(language)) {
            return msg.replace("Main.java", "Solution.java").replace("at Main.", "at Solution.");
        }
        // For C/C++, replace internal filenames
        if ("c".equalsIgnoreCase(language) || "cpp".equalsIgnoreCase(language) || "c++".equalsIgnoreCase(language)) {
            return msg.replace("prog.c", "solution.c").replace("prog.cc", "solution.cpp");
        }
        // For Python, replace internal filenames
        if ("python".equalsIgnoreCase(language)) {
            return msg.replace("prog.py", "solution.py");
        }
        // For JavaScript, replace internal filenames
        if ("javascript".equalsIgnoreCase(language) || "js".equalsIgnoreCase(language)) {
            return msg.replace("prog.js", "solution.js");
        }
        return msg;
    }

    private String safeStr(Object obj) {
        if (obj == null) return "";
        return obj.toString();
    }

    private int safeInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try { return Integer.parseInt(obj.toString()); } catch (Exception e) { return 0; }
    }

    // ================================================================
    // TEST CASE LOADER — from classpath resources
    // ================================================================
    private List<TestCase> loadTestCases(String questionId, boolean hidden) throws IOException {
        List<TestCase> list = new ArrayList<>();

        String folder = questionId.startsWith("E") ? "easy" :
                         questionId.startsWith("M") ? "medium" : "hard";

        ClassPathResource resource = new ClassPathResource("questions/" + folder + "/" + questionId + ".txt");
        if (!resource.exists()) return list;

        List<String> lines;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            lines = br.lines().toList();
        }

        String targetSection = hidden ? "HIDDEN TEST CASES:" : "SAMPLE TEST CASES:";
        String stopSection = hidden ? "CONSTRAINTS:" : "HIDDEN TEST CASES:";

        boolean inSection = false;
        boolean readingInput = false;
        boolean readingOutput = false;

        StringBuilder inputBuilder = new StringBuilder();
        StringBuilder outputBuilder = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.equals(targetSection)) {
                inSection = true;
                continue;
            }

            if (line.equals(stopSection)) break;
            if (!inSection) continue;

            if (line.equals("INPUT:")) {
                // Save previous test case if exists
                if (inputBuilder.length() > 0 && outputBuilder.length() > 0) {
                    list.add(new TestCase(inputBuilder.toString(), outputBuilder.toString()));
                    outputBuilder = new StringBuilder();
                }
                inputBuilder = new StringBuilder();
                readingInput = true;
                readingOutput = false;
                continue;
            }

            if (line.equals("OUTPUT:")) {
                outputBuilder = new StringBuilder();
                readingInput = false;
                readingOutput = true;
                continue;
            }

            if (line.equals("EXPLANATION:")) {
                readingOutput = false;
                continue;
            }

            if (readingInput) {
                inputBuilder.append(rawLine).append("\n");
            } else if (readingOutput) {
                outputBuilder.append(rawLine).append("\n");
            }
        }

        // Add last test case
        if (inputBuilder.length() > 0 && outputBuilder.length() > 0) {
            list.add(new TestCase(inputBuilder.toString(), outputBuilder.toString()));
        }

        return list;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\\s+", " ").trim();
    }

    private void updateLeaderboard(String username) {
        List<String> solved = submissionRepo.findSolvedProblemIds(username);
        List<String> attempted = submissionRepo.findAttemptedProblemIds(username);

        int easySolved = 0, mediumSolved = 0, hardSolved = 0;
        for (String id : solved) {
            if (id.startsWith("E")) easySolved++;
            else if (id.startsWith("M")) mediumSolved++;
            else if (id.startsWith("H")) hardSolved++;
        }
        int score = easySolved * 10 + mediumSolved * 20 + hardSolved * 30;

        VmLeaderboard entry = leaderboardRepo.findByUsername(username)
                .orElse(new VmLeaderboard());
        entry.setUsername(username);
        entry.setSolved(solved.size());
        entry.setAttempted(attempted.size());
        entry.setScore(score);
        entry.setUpdatedAt(LocalDateTime.now());
        leaderboardRepo.save(entry);
    }

    // ================================================================
    // INNER CLASS
    // ================================================================
    private static class TestCase {
        String input;
        String expected;
        TestCase(String input, String expected) {
            this.input = input;
            this.expected = expected;
        }
    }
}
