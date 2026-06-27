package com.dsaviz.engine;

import com.dsaviz.model.StepSnapshot;
import com.dsaviz.model.VisualizeRequest;
import com.dsaviz.model.VisualizeResponse;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Service
public class VisualizationService {

    private final CodeWrapper codeWrapper = new CodeWrapper();
    private final CompilerService compilerService = new CompilerService();
    private final JdiStepEngine stepEngine = new JdiStepEngine();

    public VisualizeResponse visualize(VisualizeRequest request) {
        Path workDir = null;
        try {
            // 1. Wrap: build the full compilable source (user code + generated Main)
            CodeWrapper.WrapResult wrapped = codeWrapper.wrap(request);

            // 2. Compile to a fresh isolated temp dir per request - never reuse
            //    dirs across requests, both for safety (don't leak state
            //    between users) and correctness (stale .class files).
            workDir = Files.createTempDirectory("dsa-viz-");
            CompilerService.CompileResult compileResult =
                    compilerService.compile(wrapped.fileName, wrapped.fullSource, workDir);

            if (!compileResult.success) {
                return VisualizeResponse.failure("compile", compileResult.diagnostics, wrapped.fullSource);
            }

            // 3. Trace via JDI
            JdiStepEngine.TraceResult traceResult = stepEngine.trace(compileResult.classOutputDir);

            Object returnValue = extractReturnValue(traceResult.stdout);

            List<String> sourceLines = Arrays.asList(request.getSolutionCode().split("\n", -1));

            VisualizeResponse response = VisualizeResponse.success(
                    traceResult.steps, returnValue, sourceLines);

            if (traceResult.truncated) {
                response.setErrorPhase("trace-truncated");
                response.setErrorMessage(
                        "Execution exceeded the step limit (possible infinite loop) - " +
                        "trace was cut short. Showing the first " + traceResult.steps.size() + " steps.");
            }

            return response;

        } catch (IllegalArgumentException | UnsupportedOperationException badInput) {
            // Signature parsing or unsupported arg type - this is a user-facing
            // input problem, not an internal error.
            return VisualizeResponse.failure("input", badInput.getMessage());
        } catch (Exception e) {
            return VisualizeResponse.failure("internal", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private Object extractReturnValue(String stdout) {
        for (String line : stdout.split("\n")) {
            if (line.startsWith("__RESULT__:")) {
                return line.substring("__RESULT__:".length());
            }
        }
        return null;
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(this::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best-effort cleanup - leftover temp dirs are not a correctness
            // issue, just disk usage; fine to ignore failures here.
        }
    }
}
