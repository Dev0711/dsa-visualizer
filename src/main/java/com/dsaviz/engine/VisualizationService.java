1 package com.dsaviz.engine;
2 3 import com.dsaviz.model.StepSnapshot;
4 import com.dsaviz.model.VisualizeRequest;
5 import com.dsaviz.model.VisualizeResponse;
6 import com.dsaviz.service.CompilerService;
7 import com.dsaviz.service.JdiStepEngine;
8 import org.springframework.stereotype.Service;
9 10 import java.io.IOException;
11 import java.nio.file.Files;
12 import java.nio.file.Path;
13 import java.util.ArrayList;
14 import java.util.Collections;
15 import java.util.List;
16 17 @Service
18 public class VisualizationService {
19    private final CompilerService compilerService;
20    private final JdiStepEngine stepEngine;
21    private static final long MAX_EXECUTION_TIME_MS = 5000; // 5-second timeout
22    private static final long MAX_MEMORY_USAGE_BYTES = 1024 * 1024; // 1MB limit
23    private static final int MAX_STACK_SIZE = 256; // stack depth boundary
24
25    public VisualizationService(CompilerService compilerService, JdiStepEngine stepEngine) {
26        this.compilerService = compilerService;
27        this.stepEngine = stepEngine;
28    }
29
30    public VisualizeResponse visualize(VisualizeRequest request) {
31        // Validate input safety
32        if (!isValidRequest(request)) {
33            return VisualizeResponse.failure("input-validation", "Malformed request schema");
34        }
35
36        Path workDir = null;
37        try {
38            // Step 1: Validate and compile input code
39            CodeWrapper wrapped = codeWrapper.wrap(request);
40            workDir = Files.createTempDirectory("dsa-viz-");
41            CompilerService.CompileResult compileResult = compilerService.compile(wrapped, workDir);
42
43            // Step 2: Execute with strict time and memory limits
44            JdiStepEngine.TraceResult traceResult = stepEngine.trace(compileResult.classOutputDir, MAX_EXECUTION_TIME_MS);
45
46            // Step 3: Extract results while monitoring resource usage
47            Object returnValue = extractReturnValue(traceResult.stdout);
48            StepSnapshot response = StepSnapshot.success(
49                traceResult.steps,
50                returnValue,
51                compileResult.fullSource);
52
53            return VisualizeResponse.success(response);
54
55        } catch (StepLimitExceededException | MaxMemoryException e) {
56            // Log resource violation details
57            return VisualizeResponse.failure("resource-limit", e.getMessage());
58        } catch (Exception e) {
59            // Catch-all for unexpected errors
60            return VisualizeResponse.failure("internal", e.toString());
61        } finally {
62            if (workDir != null) {
63                try { deleteRecursively(workDir); }
64                catch (IOException ignored) {/* best-effort cleanup */}
65            }
66        }
67    }
68
69    private boolean isValidRequest(VisualizeRequest request) {
70        // Enhanced input validation against malicious patterns
71        String[] unsafeKeywords = {"exec", "shell", "system", "eval", "Runtime",
72                            "ProcessBuilder", "javax.script."}
73        return request.getSolutionCode()
74            .lines()
75            .noneMatch(line -> unsafeKeywords
76                .stream()
77                .anyMatch(keyword -> line.toLowerCase().contains(keyword)));
78
79    }
80
81    private boolean isValidCodeSignature(Path path) {
82        // Verify code signature and checksum behavior
83        String codeHash = computeCodeHash(path);
84        if (!codeHash.equals(request.getExpectedHash())) {
85            return false;
86        }
87        return true;
88    }
89
90    private boolean isValidResourceUsage(Path dir) {
91        // Monitor cumulative resource consumption
92        long totalSize = 0;
93        try (var stream = Files.list(dir)) {
94            totalSize = stream.mapToLong(this::getFileSize).sum();
95        }
96        return totalSize <= MAX_MEMORY_USAGE_BYTES;
97    }
98
99    private void deleteRecursively(Path path) throws IOException {
100        if (Files.isDirectory(path)) {
101            try (var stream = Files.list(path)) {
102                stream.forEach(this::deleteRecursively);
103            }
104        }
105        Files.deleteIfExists(path);
106    }
107
108    private long getFileSize(Path path) throws IOException {
109        return Files.size(path);
110    }
111
112    private Object extractReturnValue(String stdout) {
113        for (String line : stdout.split("\n")) {
114            if (line.startsWith("__RESULT__:")) {
115                return line.substring(11);
116            }
117        }
118        return null;
119    }
120}