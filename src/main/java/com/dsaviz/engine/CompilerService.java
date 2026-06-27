package com.dsaviz.engine;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps javax.tools.JavaCompiler to compile a generated .java source file
 * to .class files on disk in a temp working directory.
 *
 * We compile to REAL FILES on disk (not purely in-memory) because the
 * debuggee needs to be launched as a genuine child JVM process pointed at a
 * classpath directory — JDI attaches to a running process, it doesn't take
 * compiled bytecode directly as bytes.
 */
public class CompilerService {

    public static class CompileResult {
        public final boolean success;
        public final String diagnostics;
        public final Path classOutputDir;

        private CompileResult(boolean success, String diagnostics, Path classOutputDir) {
            this.success = success;
            this.diagnostics = diagnostics;
            this.classOutputDir = classOutputDir;
        }
    }

    /**
     * @param sourceFileName e.g. "GeneratedSource.java" — must match no public
     *                       class inside (we rely on CodeWrapper keeping both
     *                       top-level classes non-public so any filename works)
     * @param sourceContent  full .java file text
     * @param workDir        a fresh temp directory dedicated to this run
     */
    public CompileResult compile(String sourceFileName, String sourceContent, Path workDir) throws IOException {
        Path sourceFile = workDir.resolve(sourceFileName);
        Files.writeString(sourceFile, sourceContent);

        Path classOutputDir = workDir.resolve("classes");
        Files.createDirectories(classOutputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // Happens if running on a JRE instead of a JDK. We require a JDK.
            throw new IllegalStateException(
                    "No system Java compiler available. Make sure you're running this " +
                    "service with a JDK (not just a JRE) — check JAVA_HOME.");
        }

        DiagnosticCollector<JavaFileObject> diagnosticsCollector = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnosticsCollector, null, null)) {

            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));

            // -g generates full debug info (line numbers + local variable
            // tables) which JDI needs to report variable names and let us
            // set line breakpoints accurately. Without -g, JDI either fails
            // to resolve local variable names or only gives you slot
            // numbers — this flag is NOT optional for this project.
            List<String> options = Arrays.asList(
                    "-g",
                    "-d", classOutputDir.toString()
            );

            Writer nullWriter = Writer.nullWriter();

            JavaCompiler.CompilationTask task = compiler.getTask(
                    nullWriter,
                    fileManager,
                    diagnosticsCollector,
                    options,
                    null,
                    compilationUnits
            );

            boolean success = task.call();

            String diagnosticsText = diagnosticsCollector.getDiagnostics().stream()
                    .map(this::formatDiagnostic)
                    .collect(Collectors.joining("\n"));

            return new CompileResult(success, diagnosticsText, classOutputDir);
        }
    }

    private String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        return String.format("[%s] line %d: %s",
                d.getKind(), d.getLineNumber(), d.getMessage(null));
    }
}
