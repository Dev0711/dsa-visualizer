package com.dsaviz.engine;

import com.dsaviz.model.StepSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the instrumented {@code Main} class in a child JVM and collects the
 * {@code STEP:{json}} lines it prints to stdout into a list of
 * {@link StepSnapshot} objects.
 *
 * <h3>How it works</h3>
 * {@link CodeInstrumentor} rewrites every statement in the user's Solution class
 * to call {@code __DSAVizTrace.step(line, method, "var1", val1, ...)} before
 * executing.  {@code __DSAVizTrace} (injected by {@link CodeWrapper}) prints
 * each step as a JSON line prefixed with {@code STEP:} to stdout.
 *
 * <p>This class:
 * <ol>
 *   <li>Launches the compiled {@code Main} class in a fresh child JVM with
 *       {@code ProcessBuilder} — no JDWP, no sockets, no JDI at all.</li>
 *   <li>Reads stdout line-by-line in the calling thread.</li>
 *   <li>Parses every {@code STEP:} line into a {@link StepSnapshot}.</li>
 *   <li>Drains stderr in a background thread to prevent pipe stalls.</li>
 *   <li>Enforces a hard wall-clock timeout and a maximum step count to guard
 *       against infinite loops in user code.</li>
 * </ol>
 *
 * <h3>No JDWP — no Docker issues</h3>
 * Unlike the previous JDI approach this class does not need to:
 * <ul>
 *   <li>allocate ephemeral ports</li>
 *   <li>attach a JDWP agent</li>
 *   <li>do any network handshaking</li>
 * </ul>
 * It simply starts a child process and reads its output — identical on
 * Windows, macOS, and Alpine Linux inside Docker.
 */
public class InstrumentedRunner {

    private static final int  MAX_STEPS      = 5000;
    private static final long TIMEOUT_MS     = 15_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class TraceResult {
        public final List<StepSnapshot> steps;
        public final String stdout;        // non-STEP lines (e.g. __RESULT__: line)
        public final boolean truncated;

        public TraceResult(List<StepSnapshot> steps, String stdout, boolean truncated) {
            this.steps     = steps;
            this.stdout    = stdout;
            this.truncated = truncated;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public TraceResult trace(Path classOutputDir) throws Exception {

        // Locate the same java binary that's running Spring Boot — avoids
        // PATH resolution surprises inside Docker containers.
        String javaHome = System.getProperty("java.home");
        String javaBin  = javaHome + File.separator + "bin" + File.separator + "java";

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classOutputDir.toAbsolutePath().toString(),
                "Main"
        );

        // Strip env vars that cause JVM startup noise (e.g. "Picked up ...")
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");

        Process process = pb.start();

        // Drain stderr in a background thread to prevent the child process from
        // stalling when its stderr pipe buffer fills up.
        StringBuilder stderrCapture = new StringBuilder();
        Thread stderrDrain = startStreamPump(process.getErrorStream(), stderrCapture);
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        // Read stdout synchronously — parse STEP: lines into snapshots,
        // accumulate non-STEP lines as plain stdout for __RESULT__ extraction.
        List<StepSnapshot> steps = new ArrayList<>();
        StringBuilder      nonStepOutput = new StringBuilder();
        boolean            hitStepLimit  = false;

        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("STEP:")) {
                    if (steps.size() < MAX_STEPS) {
                        parseStep(line.substring(5), steps.size(), steps);
                    } else {
                        hitStepLimit = true;
                    }
                } else {
                    nonStepOutput.append(line).append("\n");
                }
            }
        }

        // Wait for the child process to exit (with timeout).
        boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        boolean truncated = hitStepLimit;
        if (!finished) {
            process.destroyForcibly();
            truncated = true;
        }

        // Give the stderr drain thread a moment to flush before we return.
        stderrDrain.join(1000);

        return new TraceResult(steps, nonStepOutput.toString(), truncated);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Parses a single JSON step payload (everything after the "STEP:" prefix)
     * and appends a {@link StepSnapshot} to {@code steps}.
     *
     * <p>Expected JSON shape (produced by {@code __DSAVizTrace.step()} in the
     * instrumented source):
     * <pre>
     * {
     *   "lineNumber": 5,
     *   "methodName": "removeOuterParentheses",
     *   "variables": { "s": "(())", "level": 0, "result": "" }
     * }
     * </pre>
     */
    private void parseStep(String json, int stepIndex, List<StepSnapshot> out) {
        try {
            Map<String, Object> map = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            int    lineNumber  = (int) map.getOrDefault("lineNumber", 0);
            String methodName  = (String) map.getOrDefault("methodName", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> variables =
                    (Map<String, Object>) map.getOrDefault("variables",
                            java.util.Collections.emptyMap());

            StepSnapshot snap = new StepSnapshot(stepIndex, lineNumber, methodName);
            snap.getVariables().putAll(variables);
            out.add(snap);
        } catch (Exception ignored) {
            // Malformed JSON line — skip this step rather than crashing the trace
        }
    }

    /** Drains {@code stream} into {@code sink} line by line in the current thread. */
    private Thread startStreamPump(InputStream stream, StringBuilder sink) {
        return new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append("\n");
                }
            } catch (IOException ignored) {
                // stream closed when child process exits — expected
            }
        });
    }
}
