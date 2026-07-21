package com.dsaviz.engine;

import com.dsaviz.model.VisualizeRequest;
import com.dsaviz.model.VisualizeResponse;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Orchestrates the full visualize pipeline:
 *
 * <ol>
 *   <li>{@link CodeWrapper#wrap} — instrument the Solution source via
 *       {@link CodeInstrumentor} and assemble the full compilable source
 *       (instrumented Solution + __DSAVizTrace helper + Main harness).</li>
 *   <li>{@link CompilerService#compile} — compile to a fresh temp dir.</li>
 *   <li>{@link InstrumentedRunner#trace} — run {@code Main} in a child JVM,
 *       collect {@code STEP:{json}} lines from stdout into step snapshots.</li>
 * </ol>
 *
 * <p>No JDI / JDWP / debug ports involved — the child JVM is a plain
 * {@code java -cp <dir> Main} process.
 */
@Service
public class VisualizationService {

    private final CodeWrapper        codeWrapper  = new CodeWrapper();
    private final CompilerService    compiler     = new CompilerService();
    private final InstrumentedRunner runner       = new InstrumentedRunner();

    public VisualizeResponse visualize(VisualizeRequest request) {
        Path workDir = null;
        try {
            // 1. Instrument + wrap → full compilable source
            CodeWrapper.WrapResult wrapped = codeWrapper.wrap(request);

            // 2. Compile to a fresh isolated temp dir per request
            workDir = Files.createTempDirectory("dsa-viz-");
            CompilerService.CompileResult compileResult =
                    compiler.compile(wrapped.fileName, wrapped.fullSource, workDir);

            if (!compileResult.success) {
                return VisualizeResponse.failure("compile", compileResult.diagnostics, wrapped.fullSource);
            }

            // 3. Run the instrumented binary and collect step snapshots
            InstrumentedRunner.TraceResult traceResult =
                    runner.trace(compileResult.classOutputDir);

            Object returnValue = extractReturnValue(traceResult.stdout);

            List<String> sourceLines = Arrays.asList(request.getSolutionCode().split("\n", -1));

            VisualizeResponse response =
                    VisualizeResponse.success(traceResult.steps, returnValue, sourceLines);

            if (traceResult.truncated) {
                response.setErrorPhase("trace-truncated");
                response.setErrorMessage(
                        "Execution exceeded the step limit (possible infinite loop) — " +
                        "trace was cut short. Showing the first " +
                        traceResult.steps.size() + " steps.");
            }

            return response;

        } catch (IllegalArgumentException | UnsupportedOperationException badInput) {
            return VisualizeResponse.failure("input", badInput.getMessage());
        } catch (Exception e) {
            return VisualizeResponse.failure("internal",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    // -------------------------------------------------------------------------

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
            // Best-effort cleanup — leftover temp dirs are not a correctness issue
        }
    }
}